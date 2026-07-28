package com.inforvans.accord.controlplane.worker.temporal;

import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ReconciliationProbeServer implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger observations = new AtomicInteger();
    private final AtomicInteger mutations = new AtomicInteger();
    private final CountDownLatch releaseObservation;
    private final ReconciliationOutcome response;
    private final Object observationMonitor = new Object();

    ReconciliationProbeServer(
            ReconciliationOutcome response,
            boolean blockObservations) throws IOException {
        this.response = Objects.requireNonNull(response, "response");
        this.releaseObservation = new CountDownLatch(blockObservations ? 1 : 0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "reconciliation-probe");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/observe", this::observe);
        server.createContext("/mutate", this::mutate);
        server.start();
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    int observationCount() {
        return observations.get();
    }

    int mutationCount() {
        return mutations.get();
    }

    boolean awaitObservationCount(int expected, Duration timeout)
            throws InterruptedException {
        if (expected < 1 || timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("invalid observation wait");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (observationMonitor) {
            while (observations.get() < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(observationMonitor, remaining);
            }
            return true;
        }
    }

    void releaseObservations() {
        releaseObservation.countDown();
    }

    private void observe(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, EMPTY);
                return;
            }
            exchange.getRequestBody().readAllBytes();
            observations.incrementAndGet();
            synchronized (observationMonitor) {
                observationMonitor.notifyAll();
            }
            try {
                releaseObservation.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                respond(exchange, 503, EMPTY);
                return;
            }
            respond(exchange, 200, response.name().getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void mutate(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            mutations.incrementAndGet();
            respond(exchange, 409, EMPTY);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=US-ASCII");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        releaseObservation.countDown();
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
