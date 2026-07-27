package com.inforvans.accord.controlplane.worker.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerProbeTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @TempDir
    Path directory;

    private int uid;

    @BeforeEach
    void requireUnixAttributes() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        uid = ((Number) Files.getAttribute(directory, "unix:uid")).intValue();
    }

    @Test
    void acceptsOnlyFreshOwnedPrivateRegularState() throws Exception {
        write("worker-ready", NOW.getEpochSecond() + "\n");
        Result result = invoke(new String[] {"ready", "30"}, uid);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("worker-probe:ok\n");
    }

    @Test
    void acceptsOnlyExactProbeAndTimeoutArguments() {
        for (String[] arguments : List.of(
                new String[0],
                new String[] {"ready"},
                new String[] {"status", "30"},
                new String[] {"live", "4"},
                new String[] {"live", "301"},
                new String[] {"live", "5", "extra"},
                new String[] {"live", "NaN"})) {
            assertThat(invoke(arguments, uid).output())
                    .matches("worker-probe:(?:usage|timeout)\\n");
        }
    }

    @Test
    void rejectsMissingStaleFutureMalformedAndOversizedState() throws Exception {
        assertThat(invoke(new String[] {"live", "30"}, uid).output())
                .isEqualTo("worker-probe:missing\n");

        write("worker-live", (NOW.minusSeconds(31).getEpochSecond()) + "\n");
        assertCode("stale");
        write("worker-live", (NOW.plusSeconds(6).getEpochSecond()) + "\n");
        assertCode("future");
        write("worker-live", "not-an-epoch\n");
        assertCode("format");
        write("worker-live", NOW.getEpochSecond() + "");
        assertCode("format");
        write("worker-live", "1".repeat(32) + "\n");
        assertCode("size");
    }

    @Test
    void rejectsSymlinkDirectoryWrongModeAndOwner() throws Exception {
        Path target = write("target", NOW.getEpochSecond() + "\n");
        Files.createSymbolicLink(directory.resolve("worker-live"), target.getFileName());
        assertCode("type");

        Files.delete(directory.resolve("worker-live"));
        Files.createDirectory(directory.resolve("worker-live"));
        assertCode("type");

        Files.delete(directory.resolve("worker-live"));
        Path state = write("worker-live", NOW.getEpochSecond() + "\n");
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-r--r--"));
        assertCode("mode");

        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-------"));
        assertThat(invoke(new String[] {"live", "30"}, uid + 1).output())
                .isEqualTo("worker-probe:owner\n");
    }

    @Test
    void finishesInsideTheProcessDeadlineAndNeverLeaksInputs() throws Exception {
        write("worker-ready", "secret-path-or-content\n");
        long started = System.nanoTime();
        Result result = invoke(new String[] {"ready", "30"}, uid);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertThat(elapsedMillis).isLessThan(2_000L);
        assertThat(result.output()).isEqualTo("worker-probe:format\n");
        assertThat(result.output()).doesNotContain("secret", directory.toString(), System.getProperty("user.name"));
    }

    private Path write(String name, String contents) throws Exception {
        Path state = directory.resolve(name);
        Files.writeString(state, contents, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-------"));
        return state;
    }

    private void assertCode(String code) {
        assertThat(invoke(new String[] {"live", "30"}, uid).output())
                .isEqualTo("worker-probe:" + code + "\n");
    }

    private Result invoke(String[] arguments, int expectedUid) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.US_ASCII)) {
            exitCode = WorkerProbe.run(
                    arguments,
                    output,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    directory,
                    expectedUid);
        }
        return new Result(exitCode, bytes.toString(StandardCharsets.US_ASCII));
    }

    private record Result(int exitCode, String output) {
    }
}
