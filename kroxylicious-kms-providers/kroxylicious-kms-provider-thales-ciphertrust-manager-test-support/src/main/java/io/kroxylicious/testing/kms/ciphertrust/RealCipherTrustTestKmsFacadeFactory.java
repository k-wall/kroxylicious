/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import io.kroxylicious.kms.provider.thales.ciphertrust.CipherTrustEdek;
import io.kroxylicious.kms.provider.thales.ciphertrust.config.Config;
import io.kroxylicious.testing.kms.TestKmsFacadeFactory;

/**
 * Factory for creating real CipherTrust Manager test facades.
 * <p>
 * Uses environment variables to connect to a real CipherTrust instance.
 * See {@link RealCipherTrustTestKmsFacade} for configuration details.
 * </p>
 */
public class RealCipherTrustTestKmsFacadeFactory implements TestKmsFacadeFactory<Config, String, CipherTrustEdek> {

    @Override
    public RealCipherTrustTestKmsFacade build() {
        return new RealCipherTrustTestKmsFacade();
    }
}
