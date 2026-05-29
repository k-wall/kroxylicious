/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import java.net.URI;

import io.kroxylicious.testing.kms.TestKekManager;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Test facade for CipherTrust Manager using a WireMock-based mock server.
 * <p>
 * Always available and performs real AES-GCM encryption/decryption.
 * </p>
 */
public class CipherTrustTestKmsFacade extends AbstractCipherTrustTestKmsFacade {

    @Nullable
    private CipherTrustMockServer mockServer;

    @Nullable
    private TestKekManager kekManager;

    @Override
    public boolean isAvailable() {
        return true; // Mock server is always available
    }

    @Override
    protected void startCipherTrust() {
        mockServer = new CipherTrustMockServer();
        mockServer.start();
        kekManager = new MockCipherTrustTestKekManager(mockServer);
    }

    @Override
    protected void stopCipherTrust() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Override
    protected URI getCipherTrustUrl() {
        if (mockServer == null) {
            throw new IllegalStateException("Mock server not started");
        }
        return URI.create(mockServer.getBaseUrl());
    }

    @Override
    protected TestKekManager getKekManager() {
        if (kekManager == null) {
            throw new IllegalStateException("KEK manager not initialized");
        }
        return kekManager;
    }

    /**
     * KEK manager for mock CipherTrust server.
     */
    private static class MockCipherTrustTestKekManager implements TestKekManager {

        private final CipherTrustMockServer mockServer;

        MockCipherTrustTestKekManager(CipherTrustMockServer mockServer) {
            this.mockServer = mockServer;
        }

        @Override
        public void generateKek(String kekId) {
            mockServer.createKey(kekId);
        }

        @Override
        public void rotateKek(String kekId) {
            mockServer.rotateKey(kekId);
        }

        @Override
        public void deleteKek(String kekId) {
            mockServer.deleteKey(kekId);
        }

        @Override
        public Object read(String kekId) {
            // Return a simple marker that the key exists
            return "key:" + kekId;
        }
    }
}
