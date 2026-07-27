package com.inforvans.accord.controlplane.worker.temporal;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.EnumSet;
import java.util.Set;

public final class WindowsTemporalSecretAclPolicy implements TemporalSecretAclPolicy {
    private static final Set<AclEntryPermission> WRITE_AUTHORITY = EnumSet.of(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER);

    private final Set<UserPrincipal> approved;

    public WindowsTemporalSecretAclPolicy() throws IOException {
        UserPrincipalLookupService lookup =
            FileSystems.getDefault().getUserPrincipalLookupService();
        this.approved = Set.of(
            lookup.lookupPrincipalByName(System.getProperty("user.name")),
            lookup.lookupPrincipalByName("S-1-5-18"),
            lookup.lookupPrincipalByGroupName("S-1-5-32-544"));
    }

    WindowsTemporalSecretAclPolicy(Set<UserPrincipal> approved) {
        this.approved = Set.copyOf(approved);
    }

    @Override
    public void validate(Path path, BasicFileAttributes attributes, SecretKind kind)
            throws IOException {
        if (attributes.fileKey() == null) {
            throw new IOException("invalid Temporal secret ACL");
        }
        AclFileAttributeView view = Files.getFileAttributeView(
            path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null || view.getOwner() == null || !approved.contains(view.getOwner())) {
            throw new IOException("invalid Temporal secret ACL");
        }
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || approved.contains(entry.principal())) {
                continue;
            }
            Set<AclEntryPermission> forbidden = kind == SecretKind.PRIVATE_KEY
                ? EnumSet.allOf(AclEntryPermission.class)
                : WRITE_AUTHORITY;
            if (!java.util.Collections.disjoint(entry.permissions(), forbidden)) {
                throw new IOException("invalid Temporal secret ACL");
            }
        }
    }
}
