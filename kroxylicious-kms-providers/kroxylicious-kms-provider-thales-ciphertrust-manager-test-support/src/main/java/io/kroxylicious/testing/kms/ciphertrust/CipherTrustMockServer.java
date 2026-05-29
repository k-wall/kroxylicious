/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * WireMock-based mock server simulating CipherTrust Manager REST API.
 * <p>
 * Implements real AES-GCM encryption/decryption for realistic testing.
 * Stores KEKs in-memory and validates JWT tokens.
 * </p>
 */
public class CipherTrustMockServer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MOCK_JWT_TOKEN = "mock-jwt-token";
    private static final String MOCK_REFRESH_TOKEN = "mock-refresh-token";
    private static final int TOKEN_DURATION = 300; // 5 minutes
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final String AES_GCM_CIPHER = "AES/GCM/NoPadding";

    private final WireMockServer server;
    private final VersionedKeyStore keyStore = new VersionedKeyStore();
    private final SecureRandom secureRandom;

    /**
     * Create a CipherTrust mock server.
     */
    public CipherTrustMockServer() {
        try {
            this.secureRandom = SecureRandom.getInstanceStrong();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to initialize SecureRandom", e);
        }

        this.server = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new RandomBytesTransformer(secureRandom),
                        new EncryptTransformer(keyStore, secureRandom),
                        new DecryptTransformer(keyStore),
                        new CreateKeyTransformer(keyStore),
                        new QueryKeyTransformer(keyStore)));
    }

    /**
     * Start the mock server and configure endpoints.
     */
    public void start() {
        server.start();
        setupEndpoints();
    }

    /**
     * Stop the mock server.
     */
    public void stop() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    /**
     * Get the base URL of the mock server.
     *
     * @return base URL
     */
    public String getBaseUrl() {
        return "http://localhost:" + server.port();
    }

    private void setupEndpoints() {
        setupAuthEndpoint();
        setupRandomEndpoint();
        setupEncryptEndpoint();
        setupDecryptEndpoint();
        setupKeyManagementEndpoints();

        // Catch-all for debugging - log unmatched requests
        server.stubFor(WireMock.any(anyUrl())
                .atPriority(10) // Low priority so specific stubs match first
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("No matching stub found")));
    }

    private void setupAuthEndpoint() {
        server.stubFor(post(urlPathEqualTo("/api/v1/auth/tokens/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(createAuthResponse())));
    }

    private String createAuthResponse() {
        try {
            Map<String, Object> response = Map.of(
                    "jwt", MOCK_JWT_TOKEN,
                    "duration", TOKEN_DURATION,
                    "refresh_token", MOCK_REFRESH_TOKEN);
            return OBJECT_MAPPER.writeValueAsString(response);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to create auth response", e);
        }
    }

    private void setupRandomEndpoint() {
        server.stubFor(get(urlPathMatching("/api/v1/vault/random.*"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withTransformers("random-bytes"))); // Transformer will set body and headers
    }

    private void setupEncryptEndpoint() {
        server.stubFor(post(urlPathEqualTo("/api/v1/crypto/encrypt"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.id"))
                .withRequestBody(matchingJsonPath("$.plaintext"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withTransformers("encrypt"))); // Transformer will set body and headers
    }

    private void setupDecryptEndpoint() {
        server.stubFor(post(urlPathEqualTo("/api/v1/crypto/decrypt"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withTransformers("decrypt"))); // Transformer will set body and headers
    }

    private void setupKeyManagementEndpoints() {
        // Create key
        server.stubFor(post(urlPathEqualTo("/api/v1/vault/keys2/"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withTransformers("create-key"))); // Transformer will set body and headers

        // Query keys by name
        server.stubFor(get(urlPathMatching("/api/v1/vault/keys2.*"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withQueryParam("name", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withTransformers("query-key"))); // Transformer will set body and headers
    }

    /**
     * Pre-create a key with a specific name for testing.
     *
     * @param keyName name of the key
     */
    public void createKey(String keyName) {
        keyStore.createKey(keyName);
    }

    /**
     * Rotate a key - creates a new version.
     *
     * @param keyName name of the key
     */
    public void rotateKey(String keyName) {
        keyStore.rotateKey(keyName);
    }

    /**
     * Remove a key from the key store.
     *
     * @param keyName name of the key
     */
    public void deleteKey(String keyName) {
        keyStore.deleteKey(keyName);
    }

    private static SecretKey generateAesKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            return keyGen.generateKey();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    // Transformer implementations

    private static class RandomBytesTransformer implements ResponseDefinitionTransformerV2 {
        private final SecureRandom secureRandom;

        RandomBytesTransformer(SecureRandom secureRandom) {
            this.secureRandom = secureRandom;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                String query = serveEvent.getRequest().getUrl();
                int bytesParam = 32; // default
                if (query.contains("bytes=")) {
                    String bytesStr = query.substring(query.indexOf("bytes=") + 6);
                    if (bytesStr.contains("&")) {
                        bytesStr = bytesStr.substring(0, bytesStr.indexOf("&"));
                    }
                    bytesParam = Integer.parseInt(bytesStr);
                }

                byte[] randomBytes = new byte[bytesParam];
                secureRandom.nextBytes(randomBytes);
                String base64 = Base64.getEncoder().encodeToString(randomBytes);

                String json = OBJECT_MAPPER.writeValueAsString(Map.of("bytes", base64));
                return aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            }
            catch (Exception e) {
                throw new RuntimeException("Random bytes generation failed", e);
            }
        }

        @Override
        public String getName() {
            return "random-bytes";
        }
    }

    private static class EncryptTransformer implements ResponseDefinitionTransformerV2 {
        private final VersionedKeyStore keyStore;
        private final SecureRandom secureRandom;

        EncryptTransformer(VersionedKeyStore keyStore, SecureRandom secureRandom) {
            this.keyStore = keyStore;
            this.secureRandom = secureRandom;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(
                        serveEvent.getRequest().getBody(), Map.class);
                String keyId = (String) requestBody.get("id");
                String plaintextBase64 = (String) requestBody.get("plaintext");

                // Get latest version of KEK
                int latestVersion = keyStore.getLatestVersion(keyId);
                if (latestVersion < 0) {
                    String errorJson = OBJECT_MAPPER.writeValueAsString(Map.of("error", "Key not found"));
                    return aResponse()
                            .withStatus(404)
                            .withHeader("Content-Type", "application/json")
                            .withBody(errorJson)
                            .build();
                }
                SecretKey kek = keyStore.getKey(keyId, latestVersion);

                // Decode the base64 plaintext
                byte[] plaintext = Base64.getDecoder().decode(plaintextBase64);

                // Encrypt with AES-GCM
                byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
                secureRandom.nextBytes(iv);

                Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
                GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.ENCRYPT_MODE, kek, gcmSpec);

                byte[] ciphertextWithTag = cipher.doFinal(plaintext);
                // Split ciphertext and tag
                int ciphertextLength = ciphertextWithTag.length - (GCM_TAG_LENGTH_BITS / 8);
                byte[] ciphertext = new byte[ciphertextLength];
                byte[] tag = new byte[GCM_TAG_LENGTH_BITS / 8];
                System.arraycopy(ciphertextWithTag, 0, ciphertext, 0, ciphertextLength);
                System.arraycopy(ciphertextWithTag, ciphertextLength, tag, 0, tag.length);

                Map<String, Object> responseBody = Map.of(
                        "ciphertext", Base64.getEncoder().encodeToString(ciphertext),
                        "tag", Base64.getEncoder().encodeToString(tag),
                        "id", keyId,
                        "version", latestVersion,
                        "mode", "gcm",
                        "iv", Base64.getEncoder().encodeToString(iv));

                String json = OBJECT_MAPPER.writeValueAsString(responseBody);
                return aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            }
            catch (Exception e) {
                throw new RuntimeException("Encryption failed", e);
            }
        }

        @Override
        public String getName() {
            return "encrypt";
        }
    }

    private static class DecryptTransformer implements ResponseDefinitionTransformerV2 {
        private final VersionedKeyStore keyStore;

        DecryptTransformer(VersionedKeyStore keyStore) {
            this.keyStore = keyStore;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(
                        serveEvent.getRequest().getBody(), Map.class);
                String keyId = (String) requestBody.get("id");
                Integer version = (Integer) requestBody.get("version");
                String ciphertextBase64 = (String) requestBody.get("ciphertext");
                String tagBase64 = (String) requestBody.get("tag");
                String ivBase64 = (String) requestBody.get("iv");

                // Get specific version of KEK
                SecretKey kek = keyStore.getKey(keyId, version);
                if (kek == null) {
                    String errorJson = OBJECT_MAPPER.writeValueAsString(Map.of("error", "Key version not found"));
                    return aResponse()
                            .withStatus(404)
                            .withHeader("Content-Type", "application/json")
                            .withBody(errorJson)
                            .build();
                }

                // Decrypt with AES-GCM
                byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
                byte[] tag = Base64.getDecoder().decode(tagBase64);
                byte[] iv = Base64.getDecoder().decode(ivBase64);

                // Combine ciphertext and tag for GCM
                byte[] ciphertextWithTag = new byte[ciphertext.length + tag.length];
                System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
                System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, tag.length);

                Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
                GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.DECRYPT_MODE, kek, gcmSpec);

                byte[] plaintext = cipher.doFinal(ciphertextWithTag);

                Map<String, Object> responseBody = Map.of(
                        "plaintext", Base64.getEncoder().encodeToString(plaintext));

                String json = OBJECT_MAPPER.writeValueAsString(responseBody);
                return aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            }
            catch (Exception e) {
                throw new RuntimeException("Decryption failed", e);
            }
        }

        @Override
        public String getName() {
            return "decrypt";
        }
    }

    private static class CreateKeyTransformer implements ResponseDefinitionTransformerV2 {
        private final VersionedKeyStore keyStore;

        CreateKeyTransformer(VersionedKeyStore keyStore) {
            this.keyStore = keyStore;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(
                        serveEvent.getRequest().getBody(), Map.class);
                String name = (String) requestBody.get("name");
                String keyId = UUID.randomUUID().toString();

                // Create and store key at version 0
                keyStore.createKey(keyId);
                // Also store by name for lookup
                keyStore.createKey(name);

                Map<String, Object> responseBody = Map.of(
                        "id", keyId,
                        "name", name,
                        "algorithm", "aes");

                String json = OBJECT_MAPPER.writeValueAsString(responseBody);
                return aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            }
            catch (Exception e) {
                throw new RuntimeException("Key creation failed", e);
            }
        }

        @Override
        public String getName() {
            return "create-key";
        }
    }

    private static class QueryKeyTransformer implements ResponseDefinitionTransformerV2 {
        private final VersionedKeyStore keyStore;

        QueryKeyTransformer(VersionedKeyStore keyStore) {
            this.keyStore = keyStore;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                String query = serveEvent.getRequest().getUrl();
                String name = null;
                if (query.contains("name=")) {
                    name = query.substring(query.indexOf("name=") + 5);
                    if (name.contains("&")) {
                        name = name.substring(0, name.indexOf("&"));
                    }
                }

                String json;
                if (name != null && keyStore.containsKey(name)) {
                    // Return paginated response with one key
                    Map<String, Object> key = Map.of(
                            "id", name, // For simplicity, use name as ID in mock
                            "name", name,
                            "algorithm", "aes");
                    Map<String, Object> paginatedResponse = Map.of(
                            "skip", 0,
                            "limit", 10,
                            "total", 1,
                            "resources", new Map[] { key });
                    json = OBJECT_MAPPER.writeValueAsString(paginatedResponse);
                }
                else {
                    // Return empty paginated response
                    Map<String, Object> paginatedResponse = Map.of(
                            "skip", 0,
                            "limit", 10,
                            "total", 0);
                    // Note: resources is null when total is 0, so we omit it
                    json = OBJECT_MAPPER.writeValueAsString(paginatedResponse);
                }

                return aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)
                        .build();
            }
            catch (Exception e) {
                throw new RuntimeException("Key query failed", e);
            }
        }

        @Override
        public String getName() {
            return "query-key";
        }
    }

    /**
     * Versioned key store for CTM key versioning support.
     * Each key can have multiple versions, with version 0 being the initial version.
     */
    private static class VersionedKeyStore {
        private final Map<String, Map<Integer, SecretKey>> keys = new ConcurrentHashMap<>();
        private final Map<String, Integer> latestVersions = new ConcurrentHashMap<>();

        /**
         * Create a new key at version 0.
         */
        void createKey(String keyId) {
            Map<Integer, SecretKey> versions = new ConcurrentHashMap<>();
            versions.put(0, generateAesKey());
            keys.put(keyId, versions);
            latestVersions.put(keyId, 0);
        }

        /**
         * Rotate a key - creates a new version.
         * @return the new version number, or -1 if key doesn't exist
         */
        int rotateKey(String keyId) {
            Map<Integer, SecretKey> versions = keys.get(keyId);
            if (versions == null) {
                return -1;
            }

            int currentVersion = latestVersions.getOrDefault(keyId, 0);
            int newVersion = currentVersion + 1;

            versions.put(newVersion, generateAesKey());
            latestVersions.put(keyId, newVersion);

            return newVersion;
        }

        /**
         * Get the latest version number for a key.
         */
        int getLatestVersion(String keyId) {
            return latestVersions.getOrDefault(keyId, -1);
        }

        /**
         * Get a specific version of a key.
         */
        SecretKey getKey(String keyId, int version) {
            Map<Integer, SecretKey> versions = keys.get(keyId);
            return versions != null ? versions.get(version) : null;
        }

        /**
         * Get the latest version of a key.
         */
        SecretKey getLatestKey(String keyId) {
            int version = getLatestVersion(keyId);
            return version >= 0 ? getKey(keyId, version) : null;
        }

        /**
         * Check if a key exists.
         */
        boolean containsKey(String keyId) {
            return keys.containsKey(keyId);
        }

        /**
         * Delete a key and all its versions.
         */
        void deleteKey(String keyId) {
            keys.remove(keyId);
            latestVersions.remove(keyId);
        }
    }
}
