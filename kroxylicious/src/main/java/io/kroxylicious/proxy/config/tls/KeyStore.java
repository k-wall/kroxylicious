/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.util.Locale;
import java.util.Objects;

/**
 * Specifies Keystore TLS configuration..
 *
 * @param storeFile     location of a key store, or reference to a PEM file containing both private-key/certificate/intermediates.
 * @param storePassword password used to protect the key store. cannot be used if trustType is PEM.
 * @param keyPassword      password used to protect the key within the storeFile or privateKeyFile
 * @param storeType       specifies the server key type. Legal values are those types supported by the platform {@link java.security.KeyStore},
 *                         and PEM (for X-509 certificates express in PEM format).
 */
public record KeyStore(String storeFile,
                       PasswordProvider storePassword,
                       PasswordProvider keyPassword,
                       String storeType) {

    public static final String PEM = "PEM";

    public String getType() {
        return storeType == null ? java.security.KeyStore.getDefaultType().toUpperCase(Locale.ROOT) : storeType.toUpperCase(Locale.ROOT);
    }

    public boolean isPemType() {
        return Objects.equals(getType(), PEM);
    }
}
