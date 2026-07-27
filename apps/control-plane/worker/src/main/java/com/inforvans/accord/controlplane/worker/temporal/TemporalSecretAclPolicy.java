package com.inforvans.accord.controlplane.worker.temporal;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public interface TemporalSecretAclPolicy {
    enum SecretKind {
        DIRECTORY,
        CERTIFICATE,
        PRIVATE_KEY
    }

    void validate(Path path, BasicFileAttributes attributes, SecretKind kind) throws IOException;
}
