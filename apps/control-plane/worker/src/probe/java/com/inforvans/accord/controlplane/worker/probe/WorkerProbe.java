package com.inforvans.accord.controlplane.worker.probe;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkerProbe {
    private static final Path STATE_DIRECTORY = Path.of("/tmp/accord");
    private static final Path SELF_PROCESS = Path.of("/proc/self");
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_STATE_BYTES = 32;
    private static final int MAX_FUTURE_SKEW_SECONDS = 5;
    private static final int REQUIRED_MODE = 0600;

    private WorkerProbe() {
    }

    public static void main(String[] arguments) {
        int exitCode = runBounded(arguments, System.out, Clock.systemUTC(), STATE_DIRECTORY);
        System.exit(exitCode);
    }

    static int runBounded(
            String[] arguments, PrintStream output, Clock clock, Path stateDirectory) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worker-probe-io");
            thread.setDaemon(true);
            return thread;
        });
        String code;
        try {
            Future<String> result = executor.submit(
                    () -> inspect(arguments, clock, stateDirectory, currentUid()));
            code = result.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            code = "deadline";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            code = "interrupted";
        } catch (ExecutionException exception) {
            code = "io";
        } finally {
            executor.shutdownNow();
        }
        output.println("worker-probe:" + code);
        return "ok".equals(code) ? 0 : 1;
    }

    static int run(
            String[] arguments,
            PrintStream output,
            Clock clock,
            Path stateDirectory,
            int expectedUid) {
        String code = inspect(arguments, clock, stateDirectory, expectedUid);
        output.println("worker-probe:" + code);
        return "ok".equals(code) ? 0 : 1;
    }

    private static String inspect(
            String[] arguments, Clock clock, Path stateDirectory, int expectedUid) {
        if (arguments.length != 2
                || !("ready".equals(arguments[0]) || "live".equals(arguments[0]))) {
            return "usage";
        }
        int timeoutSeconds;
        try {
            timeoutSeconds = Integer.parseInt(arguments[1]);
        } catch (NumberFormatException exception) {
            return "timeout";
        }
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return "timeout";
        }
        if (expectedUid < 0) {
            return "platform";
        }

        Path state = stateDirectory.resolve("worker-" + arguments[0]);
        try {
            BasicFileAttributes basic = Files.readAttributes(
                    state, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!basic.isRegularFile() || basic.isSymbolicLink()) {
                return "type";
            }
            Map<String, Object> before = Files.readAttributes(
                    state, "unix:uid,mode,size,fileKey", LinkOption.NOFOLLOW_LINKS);
            if (((Number) before.get("uid")).intValue() != expectedUid) {
                return "owner";
            }
            if ((((Number) before.get("mode")).intValue() & 07777) != REQUIRED_MODE) {
                return "mode";
            }
            long size = ((Number) before.get("size")).longValue();
            if (size < 2 || size > MAX_STATE_BYTES) {
                return "size";
            }
            Object fileKey = before.get("fileKey");
            if (fileKey == null) {
                return "type";
            }

            byte[] bytes = readState(state);
            Map<String, Object> after = Files.readAttributes(
                    state, "unix:uid,mode,size,fileKey", LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(fileKey, after.get("fileKey"))
                    || !Objects.equals(before.get("uid"), after.get("uid"))
                    || !Objects.equals(before.get("mode"), after.get("mode"))
                    || !Objects.equals(before.get("size"), after.get("size"))) {
                return "changed";
            }
            if (bytes.length != size || bytes[bytes.length - 1] != '\n') {
                return "format";
            }
            for (int index = 0; index < bytes.length - 1; index++) {
                if (bytes[index] < '0' || bytes[index] > '9') {
                    return "format";
                }
            }

            long epochSeconds;
            try {
                epochSeconds = Long.parseLong(
                        new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII));
            } catch (NumberFormatException exception) {
                return "format";
            }
            long now = Instant.now(clock).getEpochSecond();
            if (epochSeconds > now + MAX_FUTURE_SKEW_SECONDS) {
                return "future";
            }
            if (epochSeconds < now - timeoutSeconds) {
                return "stale";
            }
            return "ok";
        } catch (java.nio.file.NoSuchFileException exception) {
            return "missing";
        } catch (UnsupportedOperationException exception) {
            return "platform";
        } catch (IOException | SecurityException exception) {
            return "io";
        }
    }

    private static byte[] readState(Path state) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(state, options)) {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_STATE_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // A second read detects content that grew beyond the accepted limit.
            }
            if (buffer.position() > MAX_STATE_BYTES) {
                return new byte[0];
            }
            byte[] result = new byte[buffer.position()];
            buffer.flip();
            buffer.get(result);
            return result;
        }
    }

    private static int currentUid() {
        try {
            return ((Number) Files.getAttribute(
                    SELF_PROCESS, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return -1;
        }
    }
}
