/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import java.net.URI;

import io.kroxylicious.kms.provider.thales.ciphertrust.CipherTrustEdek;
import io.kroxylicious.kms.provider.thales.ciphertrust.CipherTrustKmsService;
import io.kroxylicious.kms.provider.thales.ciphertrust.config.Config;
import io.kroxylicious.kms.provider.thales.ciphertrust.config.UserCredentials;
import io.kroxylicious.proxy.config.secret.InlinePassword;
import io.kroxylicious.testing.kms.TestKekManager;
import io.kroxylicious.testing.kms.TestKmsFacade;

/**
 * Abstract base class for CipherTrust Manager test facades.
 * <p>
 * Provides common functionality for both mock and real CipherTrust instances.
 * </p>
 */
public abstract class AbstractCipherTrustTestKmsFacade implements TestKmsFacade<Config, String, CipherTrustEdek> {

    protected static final String TEST_USERNAME = "testuser";
    protected static final String TEST_PASSWORD = "testpass";

    protected abstract void startCipherTrust();

    protected abstract void stopCipherTrust();

    protected abstract URI getCipherTrustUrl();

    protected abstract TestKekManager getKekManager();

    @Override
    public final void start() {
        startCipherTrust();
    }

    @Override
    public final void stop() {
        stopCipherTrust();
    }

    @Override
    public final Config getKmsServiceConfig() {
        UserCredentials userCredentials = new UserCredentials(
                TEST_USERNAME,
                new InlinePassword(TEST_PASSWORD));

        return new Config(
                getCipherTrustUrl(),
                userCredentials,
                null,
                null);
    }

    @Override
    public final Class<CipherTrustKmsService> getKmsServiceClass() {
        return CipherTrustKmsService.class;
    }

    @Override
    public final TestKekManager getTestKekManager() {
        return getKekManager();
    }
}
