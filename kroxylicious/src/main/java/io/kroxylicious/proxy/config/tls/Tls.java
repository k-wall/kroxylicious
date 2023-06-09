/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.util.Optional;

/**
 * Provides TLS configuration for a peer.
 *
 * @param keyStore specifies a keystore, such as JKS or PKCS12 formatted one.
 * @param keyPair specifies a key pair comprising certificate and private key.
 * @param trustStore provides the trust anchors to be used when verify a peer's public certificate.
 * @param insecureTls if set true, TLS verification will be disabled.  Not recommended for production use.
 */
public record Tls(Optional<KeyStore> keyStore,
                  Optional<KeyPair> keyPair,
                  Optional<KeyStore> trustStore,
                  Optional<Boolean> insecureTls) {
    public Tls {
        if (keyStore.isPresent() && keyPair.isPresent()) {
            throw new IllegalArgumentException("KeyStore and keyPair are mutating exclusive.  Use either keyStore to specify a key store, or keyPair to provide private-key/certificate tuple.");
        }
    }

    public boolean definesServerCertificate() {
        return keyStore.isPresent() || keyPair.isPresent();
    }
}
