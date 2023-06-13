/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

/**
 * Provides TLS configuration for a peer.
 *
 * @param key         specifies a key provider used to identify this peer can identify itself to the other party.
 * @param trust       specifies a source of trust.  If absent, platform trust is used.
 * @param insecureTls if set true, TLS verification will be disabled.  Not recommended for production use.
 */
public record Tls(KeyProvider key,
                  TrustProvider trust,
                  Boolean insecureTls) {

    public boolean definesServerCertificate() {
        return key != null;
    }
}
