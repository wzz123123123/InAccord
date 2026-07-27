package com.inforvans.accord.controlplane.worker.temporal;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import javax.net.ssl.SSLException;

public final class TemporalServiceStubsFactory {
    private final TemporalSecretFileLoader secretLoader;

    public TemporalServiceStubsFactory(TemporalSecretFileLoader secretLoader) {
        this.secretLoader = Objects.requireNonNull(secretLoader, "secretLoader");
    }

    public WorkflowServiceStubs create(TemporalConnectionProperties properties) {
        Objects.requireNonNull(properties, "properties");
        try (TemporalSecretFileLoader.SecretMaterial material = secretLoader.load(properties)) {
            SslContext sslContext = buildSslContext(material);
            WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target())
                .setEnableHttps(true)
                .setSslContext(sslContext)
                .setChannelInitializer(channel ->
                    channel.overrideAuthority(properties.serverName()))
                .setRpcTimeout(properties.rpcTimeout())
                .validateAndBuildWithDefaults();
            return WorkflowServiceStubs.newServiceStubs(options);
        } catch (IOException exception) {
            throw new IllegalStateException("invalid Temporal TLS material", exception);
        }
    }

    private static SslContext buildSslContext(
            TemporalSecretFileLoader.SecretMaterial material) throws SSLException {
        try (ByteArrayInputStream certificate =
                 new ByteArrayInputStream(material.clientCertificate());
             ByteArrayInputStream privateKey =
                 new ByteArrayInputStream(material.clientPrivateKey());
             ByteArrayInputStream trust =
                 new ByteArrayInputStream(material.trustCertificate())) {
            return GrpcSslContexts.forClient()
                .keyManager(certificate, privateKey)
                .trustManager(trust)
                .build();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory TLS stream failed", impossible);
        }
    }
}
