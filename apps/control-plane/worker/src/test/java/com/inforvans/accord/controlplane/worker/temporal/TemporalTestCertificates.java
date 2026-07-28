package com.inforvans.accord.controlplane.worker.temporal;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class TemporalTestCertificates {
    static final String SERVER_NAME = "temporal.test";

    private static final AtomicLong SERIAL = new AtomicLong(1);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Set<PosixFilePermission> PRIVATE_KEY_PERMISSIONS =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private TemporalTestCertificates() {
    }

    static Material generate(Path root) throws IOException, GeneralSecurityException {
        Files.createDirectories(root);
        secureDirectory(root);

        Authority trusted = authority("Accord Temporal test CA");
        Authority untrusted = authority("Accord untrusted Temporal CA");

        Identity server = identity(trusted, "Temporal test server", SERVER_NAME,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth);
        Identity expiredServer = identity(trusted, "Expired Temporal server", SERVER_NAME,
            NOW.minus(30, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth);
        Identity wrongSanServer = identity(trusted, "Wrong SAN Temporal server", "wrong.test",
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth);
        Identity client = identity(trusted, "Accord worker", null,
            NOW.minus(1, ChronoUnit.DAYS), NOW.plus(30, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_clientAuth);
        Identity untrustedClient = identity(untrusted, "Untrusted worker", null,
            NOW.minus(1, ChronoUnit.DAYS), NOW.plus(30, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_clientAuth);
        Identity expiredClient = identity(trusted, "Expired worker", null,
            NOW.minus(30, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_clientAuth);
        Identity wrongEkuClient = identity(trusted, "Wrong EKU worker", null,
            NOW.minus(1, ChronoUnit.DAYS), NOW.plus(30, ChronoUnit.DAYS),
            KeyPurposeId.id_kp_serverAuth);
        KeyPair mismatched = keyPair();

        Path trustedCa = write(root.resolve("trusted-ca.pem"), trusted.certificate());
        Path untrustedCa = write(root.resolve("untrusted-ca.pem"), untrusted.certificate());
        CertificateFiles serverFiles = writeIdentity(root, "server", server, trusted);
        CertificateFiles expiredServerFiles =
            writeIdentity(root, "expired-server", expiredServer, trusted);
        CertificateFiles wrongSanServerFiles =
            writeIdentity(root, "wrong-san-server", wrongSanServer, trusted);
        CertificateFiles clientFiles = writeIdentity(root, "client", client, trusted);
        CertificateFiles untrustedClientFiles =
            writeIdentity(root, "untrusted-client", untrustedClient, untrusted);
        CertificateFiles expiredClientFiles =
            writeIdentity(root, "expired-client", expiredClient, trusted);
        CertificateFiles wrongEkuClientFiles =
            writeIdentity(root, "wrong-eku-client", wrongEkuClient, trusted);
        Path mismatchedKey = writePrivateKey(root.resolve("mismatched-client-key.pem"),
            mismatched.getPrivate());

        return new Material(
            root.toRealPath(), trustedCa, untrustedCa,
            serverFiles, expiredServerFiles, wrongSanServerFiles,
            clientFiles, untrustedClientFiles, expiredClientFiles,
            wrongEkuClientFiles, mismatchedKey);
    }

    private static Authority authority(String commonName)
            throws GeneralSecurityException, IOException {
        KeyPair keys = keyPair();
        X500Name subject = new X500Name("CN=" + commonName);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject, serial(), java.util.Date.from(NOW.minus(1, ChronoUnit.DAYS)),
            java.util.Date.from(NOW.plus(365, ChronoUnit.DAYS)), subject,
            keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        X509Certificate certificate = convert(builder.build(signer(keys.getPrivate())));
        certificate.verify(keys.getPublic());
        return new Authority(keys, certificate);
    }

    private static Identity identity(
            Authority authority,
            String commonName,
            String dnsName,
            Instant notBefore,
            Instant notAfter,
            KeyPurposeId... purposes) throws GeneralSecurityException, IOException {
        KeyPair keys = keyPair();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            authority.certificate(), serial(), java.util.Date.from(notBefore),
            java.util.Date.from(notAfter), new X500Name("CN=" + commonName),
            keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage(purposes));
        if (dnsName != null) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
        }
        X509Certificate certificate = convert(builder.build(signer(authority.keys().getPrivate())));
        certificate.verify(authority.keys().getPublic());
        return new Identity(keys, certificate);
    }

    private static CertificateFiles writeIdentity(
            Path root, String name, Identity identity, Authority authority) throws IOException {
        Path certificate = write(root.resolve(name + "-cert.pem"),
            identity.certificate(), authority.certificate());
        Path privateKey = writePrivateKey(
            root.resolve(name + "-key.pem"), identity.keys().getPrivate());
        return new CertificateFiles(certificate, privateKey);
    }

    private static Path writePrivateKey(Path path, PrivateKey key) throws IOException {
        Path result = write(path, key);
        if (Files.getFileStore(result).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(result, PRIVATE_KEY_PERMISSIONS);
        }
        return result;
    }

    private static Path write(Path path, Object... values) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path);
             JcaPEMWriter pem = new JcaPEMWriter(writer)) {
            for (Object value : values) {
                pem.writeObject(value);
            }
        }
        return path.toRealPath();
    }

    private static void secureDirectory(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        }
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static BigInteger serial() {
        return BigInteger.valueOf(SERIAL.getAndIncrement());
    }

    private static ContentSigner signer(PrivateKey key) throws GeneralSecurityException {
        try {
            return new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(key);
        } catch (OperatorCreationException exception) {
            throw new GeneralSecurityException(exception);
        }
    }

    private static X509Certificate convert(X509CertificateHolder holder)
            throws GeneralSecurityException {
        try {
            return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
        } catch (java.security.cert.CertificateException exception) {
            throw new GeneralSecurityException(exception);
        }
    }

    record CertificateFiles(Path certificate, Path privateKey) {
    }

    record Material(
        Path root,
        Path trustedCa,
        Path untrustedCa,
        CertificateFiles server,
        CertificateFiles expiredServer,
        CertificateFiles wrongSanServer,
        CertificateFiles client,
        CertificateFiles untrustedClient,
        CertificateFiles expiredClient,
        CertificateFiles wrongEkuClient,
        Path mismatchedClientKey
    ) {
    }

    private record Authority(KeyPair keys, X509Certificate certificate) {
    }

    private record Identity(KeyPair keys, X509Certificate certificate) {
    }
}
