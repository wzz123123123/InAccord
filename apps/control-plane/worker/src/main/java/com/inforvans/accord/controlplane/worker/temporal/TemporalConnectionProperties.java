package com.inforvans.accord.controlplane.worker.temporal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("accord.temporal")
public record TemporalConnectionProperties(
    String endpoint,
    String namespace,
    String taskQueue,
    String serverName,
    Path secretRoot,
    Path clientCertificate,
    Path clientPrivateKey,
    Path trustCertificate,
    Duration rpcTimeout,
    Duration shutdownTimeout
) {
    public TemporalConnectionProperties {
        endpoint = required(endpoint, "endpoint");
        namespace = required(namespace, "namespace");
        taskQueue = required(taskQueue, "task-queue");
        serverName = required(serverName, "server-name");
        secretRoot = required(secretRoot, "secret-root");
        clientCertificate = required(clientCertificate, "client-certificate");
        clientPrivateKey = required(clientPrivateKey, "client-private-key");
        trustCertificate = required(trustCertificate, "trust-certificate");
        rpcTimeout = positive(rpcTimeout, "rpc-timeout");
        shutdownTimeout = positive(shutdownTimeout, "shutdown-timeout");
        parseEndpoint(endpoint);
    }

    public URI endpointUri() {
        return parseEndpoint(endpoint);
    }

    public String target() {
        URI uri = endpointUri();
        String host = uri.getHost();
        return (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host)
            + ":" + uri.getPort();
    }

    private static URI parseEndpoint(String value) {
        try {
            URI uri = new URI(value);
            if (!"grpcs".equals(uri.getScheme())
                    || uri.isOpaque()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getPort() < 1
                    || uri.getPort() > 65_535
                    || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("invalid Temporal endpoint");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid Temporal endpoint", exception);
        }
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("invalid Temporal " + property);
        }
        return value;
    }

    private static Path required(Path value, String property) {
        Objects.requireNonNull(value, "invalid Temporal " + property);
        if (value.toString().isBlank()) {
            throw new IllegalArgumentException("invalid Temporal " + property);
        }
        return value;
    }

    private static Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("invalid Temporal " + property);
        }
        return value;
    }
}
