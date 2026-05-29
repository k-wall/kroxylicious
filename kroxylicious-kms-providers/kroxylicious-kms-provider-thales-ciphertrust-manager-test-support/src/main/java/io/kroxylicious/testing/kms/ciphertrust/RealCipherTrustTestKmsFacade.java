/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kms.ciphertrust;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.umd.cs.findbugs.annotations.NonNull;

import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.proxy.config.tls.InsecureTls;
import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.testing.kms.TestKekManager;
import io.kroxylicious.testing.kms.tls.TlsHttpClientConfigurator;

/**
 * Test facade for real CipherTrust Manager instances.
 * <p>
 * Configured via environment variables:
 * </p>
 * <ul>
 * <li>CIPHERTRUST_URL - CipherTrust Manager base URL (required)</li>
 * <li>CIPHERTRUST_USERNAME - Username for authentication (default: "testuser")</li>
 * <li>CIPHERTRUST_PASSWORD - Password for authentication (default: "testpass")</li>
 * <li>CIPHERTRUST_TLS_INSECURE - Skip TLS certificate validation (default: "false")</li>
 * </ul>
 */
public class RealCipherTrustTestKmsFacade extends AbstractCipherTrustTestKmsFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ENV_URL = "CIPHERTRUST_URL";
    private static final String ENV_USERNAME = "CIPHERTRUST_USERNAME";
    private static final String ENV_PASSWORD = "CIPHERTRUST_PASSWORD";
    private static final String ENV_TLS_INSECURE = "CIPHERTRUST_TLS_INSECURE";

    private final URI cipherTrustUrl;
    private final String username;
    private final String password;
    private final boolean tlsInsecure;
    private final HttpClient httpClient;
    private String jwtToken;

    public RealCipherTrustTestKmsFacade() {
        String urlStr = System.getenv(ENV_URL);
        if (urlStr != null && !urlStr.isEmpty()) {
            this.cipherTrustUrl = URI.create(urlStr);
            this.username = System.getenv().getOrDefault(ENV_USERNAME, TEST_USERNAME);
            this.password = System.getenv().getOrDefault(ENV_PASSWORD, TEST_PASSWORD);
            this.tlsInsecure = Boolean.parseBoolean(System.getenv().getOrDefault(ENV_TLS_INSECURE, "false"));

            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30));

            TlsHttpClientConfigurator tlsConfigurator = new TlsHttpClientConfigurator(getTlsConfig());
            tlsConfigurator.apply(clientBuilder);

            this.httpClient = clientBuilder.build();
        }
        else {
            // Environment variable not set - facade is not available
            this.cipherTrustUrl = null;
            this.username = null;
            this.password = null;
            this.tlsInsecure = false;
            this.httpClient = null;
        }
    }

    @Override
    public boolean isAvailable() {
        return cipherTrustUrl != null;
    }

    @Override
    protected void startCipherTrust() {
        // Authenticate to get JWT token
        try {
            Map<String, String> authRequest = Map.of(
                    "username", username,
                    "password", password);

            String requestBody = OBJECT_MAPPER.writeValueAsString(authRequest);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(cipherTrustUrl.resolve("/api/v1/auth/tokens/"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Authentication failed with status " + response.statusCode() + ": " + response.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> authResponse = OBJECT_MAPPER.readValue(response.body(), Map.class);
            this.jwtToken = (String) authResponse.get("jwt");
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to authenticate to CipherTrust Manager", e);
        }
    }

    @Override
    protected void stopCipherTrust() {
        // Nothing to stop for a real instance
    }

    @Override
    protected URI getCipherTrustUrl() {
        if (cipherTrustUrl == null) {
            throw new IllegalStateException("RealCipherTrustTestKmsFacade is not available - " + ENV_URL + " environment variable not set");
        }
        return cipherTrustUrl;
    }

    @Override
    protected TestKekManager getKekManager() {
        return new RealCipherTrustTestKekManager();
    }

    @Override
    protected Tls getTlsConfig() {
        if (tlsInsecure) {
            return new Tls(null, new InsecureTls(true), null, null);
        }
        return null;
    }

    @Override
    protected String getUsername() {
        if (username == null) {
            throw new IllegalStateException("RealCipherTrustTestKmsFacade is not available - " + ENV_URL + " environment variable not set");
        }
        return username;
    }

    @Override
    protected String getPassword() {
        if (password == null) {
            throw new IllegalStateException("RealCipherTrustTestKmsFacade is not available - " + ENV_URL + " environment variable not set");
        }
        return password;
    }

    /**
     * Test KEK manager for real CipherTrust instances.
     * Makes actual HTTP calls to manage keys.
     */
    private class RealCipherTrustTestKekManager implements TestKekManager {

        @Override
        public void generateKek(@NonNull String kekId) {
            try {
                Map<String, Object> createKeyRequest = Map.of(
                        "name", kekId,
                        "algorithm", "aes",
                        "usageMask", 12); // encrypt + decrypt

                String requestBody = OBJECT_MAPPER.writeValueAsString(createKeyRequest);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2/"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + jwtToken)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    throw new IllegalStateException("Key creation failed with status " + response.statusCode() + ": " + response.body());
                }
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to create key: " + kekId, e);
            }
        }

        @Override
        public void rotateKek(@NonNull String kekId) {
            try {
                // First, resolve the key name to get the ID
                HttpRequest queryRequest = HttpRequest.newBuilder()
                        .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2?name=" + kekId))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + jwtToken)
                        .GET()
                        .build();

                HttpResponse<String> queryResponse = httpClient.send(queryRequest, HttpResponse.BodyHandlers.ofString());

                if (queryResponse.statusCode() == 200) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> paginatedResponse = OBJECT_MAPPER.readValue(queryResponse.body(), Map.class);
                    @SuppressWarnings("unchecked")
                    var resources = (java.util.List<Map<String, Object>>) paginatedResponse.get("resources");
                    if (resources == null || resources.isEmpty()) {
                        throw new UnknownAliasException("Key not found: " + kekId);
                    }
                    String id = (String) resources.get(0).get("id");

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2/" + id + "/versions/"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer " + jwtToken)
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() != 200 && response.statusCode() != 201) {
                        throw new IllegalStateException("Key rotation failed with status " + response.statusCode() + ": " + response.body());
                    }
                }
                else {
                    throw new UnknownAliasException("Key not found: " + kekId);
                }
            }
            catch (UnknownAliasException e) {
                throw e;
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to rotate key: " + kekId, e);
            }
        }

        @Override
        public void deleteKek(@NonNull String kekId) {
            try {
                // First, resolve the key name to get the ID
                HttpRequest queryRequest = HttpRequest.newBuilder()
                        .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2?name=" + kekId))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + jwtToken)
                        .GET()
                        .build();

                HttpResponse<String> queryResponse = httpClient.send(queryRequest, HttpResponse.BodyHandlers.ofString());

                if (queryResponse.statusCode() == 200) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> paginatedResponse = OBJECT_MAPPER.readValue(queryResponse.body(), Map.class);
                    @SuppressWarnings("unchecked")
                    var resources = (java.util.List<Map<String, Object>>) paginatedResponse.get("resources");
                    if (resources == null || resources.isEmpty()) {
                        throw new UnknownAliasException("Key not found: " + kekId);
                    }
                    String id = (String) resources.get(0).get("id");

                    HttpRequest deleteRequest = HttpRequest.newBuilder()
                            .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2/" + id))
                            .header("Authorization", "Bearer " + jwtToken)
                            .DELETE()
                            .build();

                    HttpResponse<String> deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

                    if (deleteResponse.statusCode() != 204 && deleteResponse.statusCode() != 200) {
                        throw new IllegalStateException("Key deletion failed with status " + deleteResponse.statusCode() + ": " + deleteResponse.body());
                    }
                }
                else {
                    throw new UnknownAliasException("Key not found: " + kekId);
                }
            }
            catch (UnknownAliasException e) {
                throw e;
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to delete key: " + kekId, e);
            }
        }

        @Override
        public Object read(@NonNull String kekId) {
            // Query key by name to verify it exists
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(cipherTrustUrl.resolve("/api/v1/vault/keys2?name=" + kekId))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + jwtToken)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                }
                return null;
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to read key: " + kekId, e);
            }
        }
    }
}
