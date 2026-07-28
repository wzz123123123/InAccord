package com.inforvans.accord.controlplane.worker.temporal;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class LocalTemporalPki {
    static final String SERVER_NAME = "temporal";
    private static final long WINDOWS_ACL_COMMAND_TIMEOUT_SECONDS = 10;
    private static final int WINDOWS_ACL_COMMAND_OUTPUT_LIMIT = 4_096;

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_KEY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> CERTIFICATE_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);
    private static final Set<String> EXPECTED_FILES = Set.of(
        "server-ca.pem",
        "client-ca.pem",
        "server.pem",
        "server-key.pem",
        "server-internode.pem",
        "server-internode-key.pem",
        "worker.pem",
        "worker-key.pem",
        "ui.pem",
        "ui-key.pem",
        "admin.pem",
        "admin-key.pem",
        "wrong-ca.pem",
        "wrong-ca-key.pem",
        "wrong-eku.pem",
        "wrong-eku-key.pem");

    private LocalTemporalPki() {
    }

    public static void main(String[] args) {
        try {
            generate(args);
        } catch (Exception failure) {
            System.err.print("Local Temporal PKI generation failed\n");
            System.exit(1);
        }
    }

    private static void generate(String[] args) throws Exception {
        if (args == null || args.length != 2 || !"--output-root".equals(args[0])) {
            throw new IllegalArgumentException("invalid arguments");
        }
        Path repository = repositoryRoot();
        Path expected = repository.resolve("infra/local/state/pki").normalize();
        Path requested = Path.of(args[1]).toAbsolutePath().normalize();
        if (!requested.isAbsolute() || !requested.equals(expected)) {
            throw new IllegalArgumentException("invalid output root");
        }
        Path local = repository.resolve("infra/local");
        requireExistingDirectory(repository, local);
        Path state = local.resolve("state");
        createSecureDirectory(state);
        requireDirectChild(local, state);
        applyDirectorySecurity(state);

        if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
            validateMaterial(expected);
            return;
        }

        Path temporary = state.resolve(".pki-generate-" + UUID.randomUUID());
        Files.createDirectory(temporary);
        applyDirectorySecurity(temporary);
        boolean moved = false;
        try {
            writeMaterial(temporary);
            validateMaterial(temporary);
            try {
                Files.move(temporary, expected, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (FileAlreadyExistsException concurrent) {
                validateMaterial(expected);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic PKI directory move is unavailable");
            }
            validateMaterial(expected);
        } finally {
            if (!moved) {
                deleteTree(temporary, state);
            }
        }
    }

    private static void writeMaterial(Path root) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        SecureRandom random = SecureRandom.getInstanceStrong();
        Authority serverAuthority = authority("Accord local Temporal server CA", now, random);
        Authority clientAuthority = authority("Accord local Temporal client CA", now, random);
        Authority wrongAuthority = authority("Accord local untrusted client CA", now, random);

        Identity server = identity(serverAuthority, "Accord local Temporal server",
            SERVER_NAME, KeyPurposeId.id_kp_serverAuth, now, random);
        Identity internode = identity(clientAuthority, "Accord Temporal internode client",
            null, KeyPurposeId.id_kp_clientAuth, now, random);
        Identity worker = identity(clientAuthority, "Accord Temporal worker client",
            null, KeyPurposeId.id_kp_clientAuth, now, random);
        Identity ui = identity(clientAuthority, "Accord Temporal UI client",
            null, KeyPurposeId.id_kp_clientAuth, now, random);
        Identity admin = identity(clientAuthority, "Accord Temporal namespace admin client",
            null, KeyPurposeId.id_kp_clientAuth, now, random);
        Identity wrongCa = identity(wrongAuthority, "Accord untrusted Temporal client",
            null, KeyPurposeId.id_kp_clientAuth, now, random);
        Identity wrongEku = identity(clientAuthority, "Accord wrong-EKU Temporal client",
            null, KeyPurposeId.id_kp_serverAuth, now, random);

        writeCertificates(root.resolve("server-ca.pem"), serverAuthority.certificate());
        writeCertificates(root.resolve("client-ca.pem"), clientAuthority.certificate());
        writeIdentity(root, "server", server, serverAuthority);
        writeIdentity(root, "server-internode", internode, clientAuthority);
        writeIdentity(root, "worker", worker, clientAuthority);
        writeIdentity(root, "ui", ui, clientAuthority);
        writeIdentity(root, "admin", admin, clientAuthority);
        writeIdentity(root, "wrong-ca", wrongCa, wrongAuthority);
        writeIdentity(root, "wrong-eku", wrongEku, clientAuthority);
    }

    private static Authority authority(String commonName, Instant now, SecureRandom random)
            throws Exception {
        KeyPair keys = keyPair();
        X500Name subject = new X500Name("CN=" + commonName);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject,
            serial(random),
            java.util.Date.from(now.minus(5, ChronoUnit.MINUTES)),
            java.util.Date.from(now.plus(365, ChronoUnit.DAYS)),
            subject,
            keys.getPublic());
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
            extensions.createSubjectKeyIdentifier(keys.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
            extensions.createAuthorityKeyIdentifier(keys.getPublic()));
        X509Certificate certificate = convert(builder.build(signer(keys.getPrivate())));
        certificate.verify(keys.getPublic());
        certificate.checkValidity();
        return new Authority(keys, certificate);
    }

    private static Identity identity(
            Authority authority,
            String commonName,
            String dnsName,
            KeyPurposeId purpose,
            Instant now,
            SecureRandom random) throws Exception {
        KeyPair keys = keyPair();
        X500Name issuer = X500Name.getInstance(
            authority.certificate().getSubjectX500Principal().getEncoded());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            issuer,
            serial(random),
            java.util.Date.from(now.minus(5, ChronoUnit.MINUTES)),
            java.util.Date.from(now.plus(30, ChronoUnit.DAYS)),
            new X500Name("CN=" + commonName),
            keys.getPublic());
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, true,
            new ExtendedKeyUsage(purpose));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
            extensions.createSubjectKeyIdentifier(keys.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
            extensions.createAuthorityKeyIdentifier(authority.certificate()));
        if (dnsName != null) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
        }
        X509Certificate certificate = convert(
            builder.build(signer(authority.keys().getPrivate())));
        certificate.verify(authority.certificate().getPublicKey());
        certificate.checkValidity();
        return new Identity(keys, certificate);
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static BigInteger serial(SecureRandom random) {
        BigInteger value;
        do {
            value = new BigInteger(159, random);
        } while (value.signum() <= 0);
        return value;
    }

    private static ContentSigner signer(PrivateKey key) throws GeneralSecurityException {
        try {
            return new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(key);
        } catch (OperatorCreationException failure) {
            throw new GeneralSecurityException("certificate signer unavailable", failure);
        }
    }

    private static X509Certificate convert(X509CertificateHolder holder)
            throws GeneralSecurityException {
        try {
            return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
        } catch (CertificateException failure) {
            throw new GeneralSecurityException("certificate conversion failed", failure);
        }
    }

    private static void writeIdentity(
            Path root, String name, Identity identity, Authority authority) throws IOException {
        writeCertificates(root.resolve(name + ".pem"),
            identity.certificate(), authority.certificate());
        writePrivateKey(root.resolve(name + "-key.pem"), identity.keys().getPrivate());
    }

    private static void writeCertificates(Path target, X509Certificate... certificates)
            throws IOException {
        writePem(target, (Object[]) certificates);
        applyFileSecurity(target, false);
    }

    private static void writePrivateKey(Path target, PrivateKey privateKey) throws IOException {
        writePem(target, privateKey);
        applyFileSecurity(target, true);
    }

    private static void writePem(Path target, Object... values) throws IOException {
        try (Writer writer = new OutputStreamWriter(
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE), StandardCharsets.US_ASCII);
             JcaPEMWriter pem = new JcaPEMWriter(writer)) {
            for (Object value : values) {
                pem.writeObject(value);
            }
        }
    }

    private static void validateMaterial(Path root) throws Exception {
        BasicFileAttributes rootAttributes = attributes(root);
        if (!rootAttributes.isDirectory() || !hasPlatformFileIdentity(rootAttributes)) {
            throw new IOException("invalid PKI directory");
        }
        requireDirectChild(root.getParent(), root);
        Set<String> actual = new HashSet<>();
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                actual.add(path.getFileName().toString());
            }
        }
        if (!actual.equals(EXPECTED_FILES)) {
            throw new IOException("invalid PKI file set");
        }

        TemporalSecretAclPolicy policy = aclPolicy();
        policy.validate(root, rootAttributes, TemporalSecretAclPolicy.SecretKind.DIRECTORY);
        for (String name : EXPECTED_FILES) {
            Path file = root.resolve(name);
            BasicFileAttributes fileAttributes = attributes(file);
            if (!fileAttributes.isRegularFile() || !hasPlatformFileIdentity(fileAttributes)
                    || fileAttributes.size() < 1 || fileAttributes.size() > 65_536) {
                throw new IOException("invalid PKI file");
            }
            policy.validate(file, fileAttributes,
                name.endsWith("-key.pem")
                    ? TemporalSecretAclPolicy.SecretKind.PRIVATE_KEY
                    : TemporalSecretAclPolicy.SecretKind.CERTIFICATE);
            if (name.endsWith("-key.pem")) {
                requirePrivateKey(file);
            }
        }

        X509Certificate serverCa = firstCertificate(root.resolve("server-ca.pem"));
        X509Certificate clientCa = firstCertificate(root.resolve("client-ca.pem"));
        requireAuthority(serverCa);
        requireAuthority(clientCa);
        requireIdentity(root.resolve("server.pem"), serverCa,
            KeyPurposeId.id_kp_serverAuth.getId(), SERVER_NAME);
        for (String name : List.of("server-internode", "worker", "ui", "admin")) {
            requireIdentity(root.resolve(name + ".pem"), clientCa,
                KeyPurposeId.id_kp_clientAuth.getId(), null);
        }
        Collection<X509Certificate> wrongChain = certificates(root.resolve("wrong-ca.pem"));
        if (wrongChain.size() != 2) {
            throw new CertificateException("invalid untrusted client chain");
        }
        X509Certificate wrongCa = new ArrayList<>(wrongChain).get(1);
        requireAuthority(wrongCa);
        requireIdentity(root.resolve("wrong-ca.pem"), wrongCa,
            KeyPurposeId.id_kp_clientAuth.getId(), null);
        requireIdentity(root.resolve("wrong-eku.pem"), clientCa,
            KeyPurposeId.id_kp_serverAuth.getId(), null);
    }

    private static void requireAuthority(X509Certificate certificate) throws Exception {
        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
        if (certificate.getBasicConstraints() != 0
                || certificate.getKeyUsage() == null
                || !certificate.getKeyUsage()[5]) {
            throw new CertificateException("invalid certificate authority");
        }
    }

    private static void requireIdentity(
            Path path, X509Certificate authority, String purpose, String dnsName)
            throws Exception {
        List<X509Certificate> chain = new ArrayList<>(certificates(path));
        if (chain.size() != 2 || !chain.get(1).equals(authority)) {
            throw new CertificateException("invalid certificate chain");
        }
        X509Certificate identity = chain.get(0);
        identity.checkValidity();
        identity.verify(authority.getPublicKey());
        if (identity.getBasicConstraints() != -1
                || identity.getExtendedKeyUsage() == null
                || !identity.getExtendedKeyUsage().equals(List.of(purpose))) {
            throw new CertificateException("invalid certificate purpose");
        }
        Collection<List<?>> names = identity.getSubjectAlternativeNames();
        if (dnsName == null) {
            if (names != null && !names.isEmpty()) {
                throw new CertificateException("unexpected certificate SAN");
            }
        } else if (names == null || names.size() != 1
                || !Integer.valueOf(GeneralName.dNSName).equals(names.iterator().next().get(0))
                || !dnsName.equals(names.iterator().next().get(1))) {
            throw new CertificateException("invalid certificate SAN");
        }
    }

    private static Collection<X509Certificate> certificates(Path path)
            throws IOException, CertificateException {
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            Collection<? extends java.security.cert.Certificate> parsed =
                CertificateFactory.getInstance("X.509").generateCertificates(input);
            List<X509Certificate> result = new ArrayList<>();
            for (java.security.cert.Certificate certificate : parsed) {
                result.add((X509Certificate) certificate);
            }
            return List.copyOf(result);
        }
    }

    private static X509Certificate firstCertificate(Path path)
            throws IOException, CertificateException {
        Collection<X509Certificate> parsed = certificates(path);
        if (parsed.size() != 1) {
            throw new CertificateException("invalid CA certificate file");
        }
        return parsed.iterator().next();
    }

    private static void requirePrivateKey(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII);
             PEMParser parser = new PEMParser(reader)) {
            Object key = parser.readObject();
            if (!(key instanceof PEMKeyPair) && !(key instanceof PrivateKeyInfo)
                    || parser.readObject() != null) {
                throw new IOException("invalid private key PEM");
            }
        } catch (RuntimeException malformed) {
            throw new IOException("invalid private key PEM");
        }
    }

    private static TemporalSecretAclPolicy aclPolicy() throws IOException {
        return isWindows()
            ? new WindowsTemporalSecretAclPolicy()
            : new PosixTemporalSecretAclPolicy();
    }

    private static void createSecureDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException existing) {
            BasicFileAttributes attributes = attributes(directory);
            if (!attributes.isDirectory()) {
                throw existing;
            }
        }
    }

    private static void applyDirectorySecurity(Path directory) throws IOException {
        if (isWindows()) {
            applyWindowsAcl(directory);
        } else {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        }
    }

    private static void applyFileSecurity(Path file, boolean privateKey) throws IOException {
        if (isWindows()) {
            applyWindowsAcl(file);
        } else {
            Files.setPosixFilePermissions(file,
                privateKey ? PRIVATE_KEY_PERMISSIONS : CERTIFICATE_PERMISSIONS);
        }
    }

    private static void applyWindowsAcl(Path path) throws IOException {
        removeWindowsAclInheritance(path);
        UserPrincipalLookupService lookup =
            FileSystems.getDefault().getUserPrincipalLookupService();
        UserPrincipal current = lookup.lookupPrincipalByName(System.getProperty("user.name"));
        UserPrincipal system = lookupPrincipalByName(
            lookup, "S-1-5-18", "NT AUTHORITY\\SYSTEM");
        UserPrincipal administrators = lookupPrincipalByGroupName(
            lookup, "S-1-5-32-544", "BUILTIN\\Administrators");
        AclFileAttributeView view = Files.getFileAttributeView(
            path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Windows ACL view unavailable");
        }
        if (!current.equals(view.getOwner())) {
            view.setOwner(current);
        }
        Set<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
        view.setAcl(List.of(
            allow(current, permissions),
            allow(system, permissions),
            allow(administrators, permissions)));
    }

    private static UserPrincipal lookupPrincipalByName(
            UserPrincipalLookupService lookup, String sid, String fallbackName)
            throws IOException {
        try {
            return lookup.lookupPrincipalByName(sid);
        } catch (UserPrincipalNotFoundException missingSid) {
            return lookup.lookupPrincipalByName(fallbackName);
        }
    }

    private static UserPrincipal lookupPrincipalByGroupName(
            UserPrincipalLookupService lookup, String sid, String fallbackName)
            throws IOException {
        try {
            return lookup.lookupPrincipalByGroupName(sid);
        } catch (UserPrincipalNotFoundException missingSid) {
            return lookup.lookupPrincipalByGroupName(fallbackName);
        }
    }

    private static void removeWindowsAclInheritance(Path path) throws IOException {
        Process process = new ProcessBuilder(
            "icacls.exe", path.toString(), "/inheritance:r")
            .redirectErrorStream(true)
            .start();
        try {
            if (!process.waitFor(WINDOWS_ACL_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (process.waitFor(WINDOWS_ACL_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    discardWindowsAclCommandOutput(process);
                } else {
                    process.getInputStream().close();
                }
                throw new IOException("Windows ACL inheritance removal timed out");
            }
            discardWindowsAclCommandOutput(process);
            if (process.exitValue() != 0) {
                throw new IOException("Windows ACL inheritance removal failed");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Windows ACL inheritance removal interrupted", interrupted);
        }
    }

    private static void discardWindowsAclCommandOutput(Process process) throws IOException {
        try (var output = process.getInputStream()) {
            output.readNBytes(WINDOWS_ACL_COMMAND_OUTPUT_LIMIT);
        }
    }

    private static AclEntry allow(
            UserPrincipal principal, Set<AclEntryPermission> permissions) {
        return AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(permissions)
            .build();
    }

    private static void requireExistingDirectory(Path repository, Path directory)
            throws IOException {
        if (!directory.startsWith(repository)) {
            throw new IOException("directory escapes repository");
        }
        Path current = repository;
        for (Path part : repository.relativize(directory)) {
            current = current.resolve(part);
            BasicFileAttributes attributes = attributes(current);
            if (!attributes.isDirectory() || !hasPlatformFileIdentity(attributes)) {
                throw new IOException("invalid repository directory");
            }
        }
    }

    private static void requireDirectChild(Path parent, Path child) throws IOException {
        Path realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realChild = child.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realChild.getParent().equals(realParent)) {
            throw new IOException("path is not a direct child");
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IOException("symbolic links are forbidden");
        }
        return attributes;
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().normalize().toRealPath();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("settings.gradle"),
                    LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(current.resolve("infra/local"),
                        LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("repository root unavailable");
    }

    private static void deleteTree(Path target, Path allowedParent) throws IOException {
        if (!target.normalize().getParent().equals(allowedParent.normalize())
                || !target.getFileName().toString().startsWith(".pki-generate-")) {
            throw new IOException("refusing unsafe temporary cleanup");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                BasicFileAttributes attributes = attributes(path);
                if (attributes.isSymbolicLink()) {
                    throw new IOException("temporary PKI contains a symlink");
                }
                Files.delete(path);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static boolean hasPlatformFileIdentity(BasicFileAttributes attributes) {
        return isWindows() || attributes.fileKey() != null;
    }

    private record Authority(KeyPair keys, X509Certificate certificate) {
    }

    private record Identity(KeyPair keys, X509Certificate certificate) {
    }
}
