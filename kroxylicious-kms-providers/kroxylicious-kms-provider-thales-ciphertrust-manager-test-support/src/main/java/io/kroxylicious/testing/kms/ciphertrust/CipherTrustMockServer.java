/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
    private final Map<String, SecretKey> keyStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom;

    /**
     * Create a CipherTrust mock server.
     */
    public CipherTrustMockServer() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        try {
            this.secureRandom = SecureRandom.getInstanceStrong();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to initialize SecureRandom", e);
        }
    }

    /**
     * Start the mock server and configure endpoints.
     */
    public void start() {
        server.start();
        WireMock.configureFor("localhost", server.port());
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
    }

    private void setupAuthEndpoint() {
        // Handle both username/password and refresh_token authentication
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
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("random-bytes-transformer")));

        // Register transformer for random bytes generation
        server.addStubMapping(WireMock.get(urlPathMatching("/api/v1/vault/random.*"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody((request) -> {
                            // Extract bytes parameter from query string
                            String query = request.getUrl();
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

                            try {
                                return OBJECT_MAPPER.writeValueAsString(Map.of("bytes", base64));
                            }
                            catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                .build());
    }

    private void setupEncryptEndpoint() {
        server.stubFor(post(urlPathEqualTo("/api/v1/crypto/encrypt"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.id"))
                .withRequestBody(matchingJsonPath("$.plaintext"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody((request) -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(request.getBody(), Map.class);
                                String keyId = (String) requestBody.get("id");
                                String plaintextBase64 = (String) requestBody.get("plaintext");

                                // Get or create KEK
                                SecretKey kek = keyStore.computeIfAbsent(keyId, k -> generateAesKey());

                                // Decrypt the base64 plaintext
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

                                Map<String, Object> response = Map.of(
                                        "ciphertext", Base64.getEncoder().encodeToString(ciphertext),
                                        "tag", Base64.getEncoder().encodeToString(tag),
                                        "id", keyId,
                                        "version", 0,
                                        "mode", "gcm",
                                        "iv", Base64.getEncoder().encodeToString(iv));

                                return OBJECT_MAPPER.writeValueAsString(response);
                            }
                            catch (Exception e) {
                                throw new RuntimeException("Encryption failed", e);
                            }
                        })));
    }

    private void setupDecryptEndpoint() {
        server.stubFor(post(urlPathEqualTo("/api/v1/crypto/decrypt"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody((request) -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(request.getBody(), Map.class);
                                String keyId = (String) requestBody.get("id");
                                String ciphertextBase64 = (String) requestBody.get("ciphertext");
                                String tagBase64 = (String) requestBody.get("tag");
                                String ivBase64 = (String) requestBody.get("iv");

                                // Get KEK
                                SecretKey kek = keyStore.get(keyId);
                                if (kek == null) {
                                    return OBJECT_MAPPER.writeValueAsString(Map.of("error", "Key not found"));
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

                                Map<String, Object> response = Map.of(
                                        "plaintext", Base64.getEncoder().encodeToString(plaintext));

                                return OBJECT_MAPPER.writeValueAsString(response);
                            }
                            catch (Exception e) {
                                throw new RuntimeException("Decryption failed", e);
                            }
                        })));
    }

    private void setupKeyManagementEndpoints() {
        // Create key
        server.stubFor(post(urlPathEqualTo("/api/v1/vault/keys2/"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody((request) -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> requestBody = OBJECT_MAPPER.readValue(request.getBody(), Map.class);
                                String name = (String) requestBody.get("name");
                                String keyId = UUID.randomUUID().toString();

                                // Create and store key
                                SecretKey key = generateAesKey();
                                keyStore.put(keyId, key);
                                // Also store by name for lookup
                                keyStore.put(name, key);

                                Map<String, Object> response = Map.of(
                                        "id", keyId,
                                        "name", name,
                                        "algorithm", "aes");

                                return OBJECT_MAPPER.writeValueAsString(response);
                            }
                            catch (Exception e) {
                                throw new RuntimeException("Key creation failed", e);
                            }
                        })));

        // Query keys by name
        server.stubFor(get(urlPathMatching("/api/v1/vault/keys2.*"))
                .withHeader("Authorization", equalTo("Bearer " + MOCK_JWT_TOKEN))
                .withQueryParam("name", WireMock.matching(".*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody((request) -> {
                            try {
                                String query = request.getUrl();
                                String name = null;
                                if (query.contains("name=")) {
                                    name = query.substring(query.indexOf("name=") + 5);
                                    if (name.contains("&")) {
                                        name = name.substring(0, name.indexOf("&"));
                                    }
                                }

                                if (name != null && keyStore.containsKey(name)) {
                                    // Return array with one key
                                    Map<String, Object> key = Map.of(
                                            "id", name, // For simplicity, use name as ID in mock
                                            "name", name,
                                            "algorithm", "aes");
                                    return OBJECT_MAPPER.writeValueAsString(new Map[] { key });
                                }
                                else {
                                    // Return empty array
                                    return "[]";
                                }
                            }
                            catch (Exception e) {
                                throw new RuntimeException("Key query failed", e);
                            }
                        })));
    }

    /**
     * Pre-create a key with a specific name for testing.
     *
     * @param keyName name of the key
     */
    public void createKey(String keyName) {
        SecretKey key = generateAesKey();
        keyStore.put(keyName, key);
    }

    /**
     * Remove a key from the key store.
     *
     * @param keyName name of the key
     */
    public void deleteKey(String keyName) {
        keyStore.remove(keyName);
    }

    private SecretKey generateAesKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            return keyGen.generateKey();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }
}
