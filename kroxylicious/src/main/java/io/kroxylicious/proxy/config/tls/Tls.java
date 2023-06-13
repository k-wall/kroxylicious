/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

/**
 * Provides TLS configuration for this peer.
 *
 * @param key   specifies a key provider that provides the certificate/key used to identify this peer.
 * @param trust specifies a trust provider used by this peer to determine whether to trust the peer.
 */
public record Tls(KeyProvider key,
                  TrustProvider trust
) {

    public boolean definesKey() {
        return key != null;
    }
}
