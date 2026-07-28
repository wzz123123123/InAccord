package com.inforvans.accord.controlplane.worker.temporal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PosixTemporalSecretAclPolicy implements TemporalSecretAclPolicy {
    private static final int GROUP_READ = 0040;
    private static final int GROUP_WRITE = 0020;
    private static final int GROUP_EXECUTE = 0010;
    private static final int OTHER_MASK = 0007;

    private final int processUid;
    private final Set<Integer> processGroups;

    public PosixTemporalSecretAclPolicy() throws IOException {
        this(processIdentity("uid"), processGroups());
    }

    PosixTemporalSecretAclPolicy(int processUid, Set<Integer> processGroups) {
        this.processUid = processUid;
        this.processGroups = Set.copyOf(processGroups);
    }

    @Override
    public void validate(Path path, BasicFileAttributes attributes, SecretKind kind)
            throws IOException {
        if (attributes.fileKey() == null) {
            throw new IOException("invalid Temporal secret ACL");
        }
        Map<String, Object> unix = Files.readAttributes(
            path, "unix:uid,gid,mode", LinkOption.NOFOLLOW_LINKS);
        int uid = number(unix, "uid");
        int gid = number(unix, "gid");
        int mode = number(unix, "mode") & 0777;
        if (uid != 0 && uid != processUid) {
            throw new IOException("invalid Temporal secret ACL");
        }
        switch (kind) {
            case DIRECTORY, CERTIFICATE -> {
                if ((mode & (GROUP_WRITE | 0002)) != 0) {
                    throw new IOException("invalid Temporal secret ACL");
                }
            }
            case PRIVATE_KEY -> {
                if ((mode & OTHER_MASK) != 0
                        || (mode & (GROUP_WRITE | GROUP_EXECUTE)) != 0
                        || ((mode & GROUP_READ) != 0 && !processGroups.contains(gid))) {
                    throw new IOException("invalid Temporal secret ACL");
                }
            }
            default -> throw new IOException("invalid Temporal secret ACL");
        }
    }

    private static int processIdentity(String attribute) throws IOException {
        return number(Files.readAttributes(
            Path.of("."), "unix:" + attribute, LinkOption.NOFOLLOW_LINKS), attribute);
    }

    private static Set<Integer> processGroups() throws IOException {
        Set<Integer> groups = new HashSet<>();
        groups.add(processIdentity("gid"));
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) {
            return groups;
        }
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("Groups:")) {
                for (String value : line.substring("Groups:".length()).trim().split("\\s+")) {
                    if (!value.isEmpty()) {
                        groups.add(Integer.parseInt(value));
                    }
                }
                return groups;
            }
        }
        throw new IOException("unable to resolve process groups");
    }

    private static int number(Map<String, Object> attributes, String name) throws IOException {
        Object value = attributes.get(name);
        if (!(value instanceof Number number)) {
            throw new IOException("invalid Temporal secret ACL");
        }
        return number.intValue();
    }
}
