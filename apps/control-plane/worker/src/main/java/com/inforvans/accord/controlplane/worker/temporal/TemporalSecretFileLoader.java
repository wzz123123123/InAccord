package com.inforvans.accord.controlplane.worker.temporal;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TemporalSecretFileLoader {
    static final int MAX_SECRET_BYTES = 65_536;

    private final TemporalSecretAclPolicy aclPolicy;
    private final boolean windows;

    public TemporalSecretFileLoader(TemporalSecretAclPolicy aclPolicy) {
        this.aclPolicy = Objects.requireNonNull(aclPolicy, "aclPolicy");
        this.windows = System.getProperty("os.name", "").startsWith("Windows");
    }

    public SecretMaterial load(TemporalConnectionProperties properties) throws IOException {
        Objects.requireNonNull(properties, "properties");
        byte[] certificate = null;
        byte[] privateKey = null;
        byte[] trust = null;
        try {
            certificate = load(properties.secretRoot(), properties.clientCertificate(),
                TemporalSecretAclPolicy.SecretKind.CERTIFICATE);
            privateKey = load(properties.secretRoot(), properties.clientPrivateKey(),
                TemporalSecretAclPolicy.SecretKind.PRIVATE_KEY);
            trust = load(properties.secretRoot(), properties.trustCertificate(),
                TemporalSecretAclPolicy.SecretKind.CERTIFICATE);
            return new SecretMaterial(certificate, privateKey, trust);
        } catch (IOException | RuntimeException failure) {
            clear(certificate);
            clear(privateKey);
            clear(trust);
            throw failure;
        }
    }

    public byte[] load(Path allowedRoot, Path configuredPath,
            TemporalSecretAclPolicy.SecretKind kind) throws IOException {
        String property = property(kind);
        try {
            Path rootInput = Objects.requireNonNull(allowedRoot, property).toAbsolutePath().normalize();
            Path root = rootInput.toRealPath();
            BasicFileAttributes rootAttributes = attributes(root);
            if (!rootAttributes.isDirectory()) {
                throw new IOException(property);
            }
            aclPolicy.validate(root, rootAttributes, TemporalSecretAclPolicy.SecretKind.DIRECTORY);

            Path input = Objects.requireNonNull(configuredPath, property);
            Path candidate = (input.isAbsolute() ? input : rootInput.resolve(input))
                .toAbsolutePath().normalize();
            if (!candidate.startsWith(rootInput)) {
                throw new IOException(property);
            }
            List<Hop> beforeHops = snapshotHops(rootInput, candidate);
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw new IOException(property);
            }
            validateDirectories(root, real.getParent());
            Snapshot before = snapshot(real, kind);
            if (!before.attributes().isRegularFile()
                    || before.attributes().size() < 1
                    || before.attributes().size() > MAX_SECRET_BYTES) {
                throw new IOException(property);
            }

            byte[] result = new byte[(int) before.attributes().size()];
            try (SeekableByteChannel channel = openStable(root, real)) {
                if (channel.size() != result.length) {
                    throw new IOException(property);
                }
                ByteBuffer buffer = ByteBuffer.wrap(result);
                while (buffer.hasRemaining()) {
                    int count = channel.read(buffer);
                    if (count < 0) {
                        throw new IOException(property);
                    }
                }
                if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != result.length) {
                    throw new IOException(property);
                }
                Snapshot after = snapshot(real, kind);
                if (!before.sameIdentity(after)
                        || !beforeHops.equals(snapshotHops(rootInput, candidate))) {
                    throw new IOException(property);
                }
                return result;
            } catch (IOException | RuntimeException failure) {
                clear(result);
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            throw new IOException(property, failure);
        }
    }

    private SeekableByteChannel openStable(Path root, Path real) throws IOException {
        if (windows) {
            Set<OpenOption> options = Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_DELETE);
            return FileChannel.open(real, options);
        }
        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
        if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
            rootStream.close();
            throw new IOException("Temporal secret secure directory unavailable");
        }
        SecureDirectoryStream<Path> current = secureRoot;
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        opened.add(secureRoot);
        try {
            Path relative = root.relativize(real);
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                current = current.newDirectoryStream(
                    relative.getName(index), LinkOption.NOFOLLOW_LINKS);
                opened.add(current);
            }
            SeekableByteChannel channel = current.newByteChannel(
                relative.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            return new ClosingChannel(channel, opened);
        } catch (IOException | RuntimeException failure) {
            closeReverse(opened);
            throw failure;
        }
    }

    private void validateDirectories(Path root, Path parent) throws IOException {
        if (parent == null || !parent.startsWith(root)) {
            throw new IOException("Temporal secret path");
        }
        Path current = root;
        aclPolicy.validate(current, attributes(current),
            TemporalSecretAclPolicy.SecretKind.DIRECTORY);
        for (Path name : root.relativize(parent)) {
            current = current.resolve(name);
            BasicFileAttributes attributes = attributes(current);
            if (!attributes.isDirectory()) {
                throw new IOException("Temporal secret path");
            }
            aclPolicy.validate(current, attributes,
                TemporalSecretAclPolicy.SecretKind.DIRECTORY);
        }
    }

    private Snapshot snapshot(Path path, TemporalSecretAclPolicy.SecretKind kind)
            throws IOException {
        BasicFileAttributes basic = attributes(path);
        aclPolicy.validate(path, basic, kind);
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        Object security;
        if (windows) {
            AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("Temporal secret ACL unavailable");
            }
            security = List.copyOf(view.getAcl());
        } else {
            security = Map.copyOf(Files.readAttributes(
                path, "unix:uid,gid,mode", LinkOption.NOFOLLOW_LINKS));
        }
        return new Snapshot(path, basic, owner, security);
    }

    private static List<Hop> snapshotHops(Path root, Path candidate) throws IOException {
        List<Hop> hops = new ArrayList<>();
        Path current = root;
        hops.add(Hop.capture(current));
        for (Path name : root.relativize(candidate)) {
            current = current.resolve(name);
            hops.add(Hop.capture(current));
        }
        return List.copyOf(hops);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String property(TemporalSecretAclPolicy.SecretKind kind) {
        return kind == TemporalSecretAclPolicy.SecretKind.PRIVATE_KEY
            ? "client-private-key" : "certificate";
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void closeReverse(List<? extends AutoCloseable> resources) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception ignored) {
                // A read failure remains authoritative.
            }
        }
    }

    public static final class SecretMaterial implements AutoCloseable {
        private final byte[] clientCertificate;
        private final byte[] clientPrivateKey;
        private final byte[] trustCertificate;

        private SecretMaterial(byte[] clientCertificate, byte[] clientPrivateKey,
                byte[] trustCertificate) {
            this.clientCertificate = clientCertificate;
            this.clientPrivateKey = clientPrivateKey;
            this.trustCertificate = trustCertificate;
        }

        public byte[] clientCertificate() {
            return clientCertificate;
        }

        public byte[] clientPrivateKey() {
            return clientPrivateKey;
        }

        public byte[] trustCertificate() {
            return trustCertificate;
        }

        @Override
        public void close() {
            clear(clientPrivateKey);
        }
    }

    private record Snapshot(
        Path path,
        BasicFileAttributes attributes,
        UserPrincipal owner,
        Object security
    ) {
        boolean sameIdentity(Snapshot other) {
            return path.equals(other.path)
                && Objects.equals(attributes.fileKey(), other.attributes.fileKey())
                && attributes.size() == other.attributes.size()
                && attributes.lastModifiedTime().equals(other.attributes.lastModifiedTime())
                && owner.equals(other.owner)
                && security.equals(other.security);
        }
    }

    private record Hop(Path path, Object fileKey, long size, long modified,
            boolean symbolicLink, Path linkTarget) {
        static Hop capture(Path path) throws IOException {
            BasicFileAttributes attributes = TemporalSecretFileLoader.attributes(path);
            boolean link = attributes.isSymbolicLink();
            return new Hop(path, attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime().toMillis(), link,
                link ? Files.readSymbolicLink(path) : null);
        }
    }

    private static final class ClosingChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final List<SecureDirectoryStream<Path>> directories;

        private ClosingChannel(SeekableByteChannel delegate,
                List<SecureDirectoryStream<Path>> directories) {
            this.delegate = delegate;
            this.directories = List.copyOf(directories);
        }

        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public SeekableByteChannel position(long value) throws IOException {
            delegate.position(value);
            return this;
        }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() throws IOException {
            IOException failure = null;
            try {
                delegate.close();
            } catch (IOException exception) {
                failure = exception;
            }
            closeReverse(directories);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
