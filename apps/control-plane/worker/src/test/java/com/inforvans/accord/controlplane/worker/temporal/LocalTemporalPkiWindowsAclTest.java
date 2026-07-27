package com.inforvans.accord.controlplane.worker.temporal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class LocalTemporalPkiWindowsAclTest {
    private static final Duration ICACLS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void removesInheritedAuthenticatedUsersModifyAccessBeforeApplyingPrivateKeyAcl()
            throws Exception {
        Path parent = Files.createTempDirectory("accord-pki-acl-");
        try {
            grantAuthenticatedUsersModify(parent);
            Path privateKey = parent.resolve("private-key.pem");
            Files.writeString(privateKey, "private key", StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            assertThrows(IOException.class, () -> new WindowsTemporalSecretAclPolicy().validate(
                privateKey, attributes(privateKey), TemporalSecretAclPolicy.SecretKind.PRIVATE_KEY));
            org.junit.jupiter.api.Assertions.assertTrue(hasInheritedAccess(privateKey));

            applyWindowsAcl(privateKey);

            org.junit.jupiter.api.Assertions.assertFalse(hasInheritedAccess(privateKey));
            new WindowsTemporalSecretAclPolicy().validate(privateKey, attributes(privateKey),
                TemporalSecretAclPolicy.SecretKind.PRIVATE_KEY);
        } finally {
            deleteTree(parent);
        }
    }

    private static void grantAuthenticatedUsersModify(Path path) throws Exception {
        Process process = new ProcessBuilder(
            "icacls.exe", path.toString(), "/grant", "*S-1-5-11:(OI)(CI)M")
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(ICACLS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("icacls grant timed out");
        }
        String output = new String(process.getInputStream().readNBytes(4_096),
            StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("icacls grant failed: " + output);
        }
    }

    private static void applyWindowsAcl(Path path) throws Exception {
        Method method = LocalTemporalPki.class.getDeclaredMethod("applyWindowsAcl", Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, path);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasInheritedAccess(Path path) throws Exception {
        Process process = new ProcessBuilder("icacls.exe", path.toString())
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(ICACLS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("icacls query timed out");
        }
        String output = new String(process.getInputStream().readNBytes(4_096),
            StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("icacls query failed: " + output);
        }
        return output.contains("(I)");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
