/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.thales.ciphertrust;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.kms.provider.azure.auth.BearerTokenService;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.DecryptRequest;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.DecryptResponse;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.EncryptRequest;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.EncryptResponse;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.GetKeyResponse;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.GetKeysResponse;
import io.kroxylicious.kms.provider.thales.ciphertrust.model.RandomResponse;
import io.kroxylicious.kms.service.DekPair;
import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.Serde;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.kms.service.UnknownKeyException;

/**
 * Implementation of {@link Kms} backed by Thales CipherTrust Manager.
 * <p>
 * Implements envelope encryption using CTM's primitive cryptographic operations:
 * </p>
 * <ul>
 *   <li>Generate random DEK bytes via {@code /api/v1/vault/random}</li>
 *   <li>Encrypt DEK with KEK via {@code /api/v1/crypto/encrypt}</li>
 *   <li>Decrypt EDEK via {@code /api/v1/crypto/decrypt}</li>
 * </ul>
 */
public class CipherTrustKms implements Kms<String, CipherTrustEdek> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CipherTrustKms.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AES_KEY_ALGO = "AES";
    private static final int DEK_SIZE_BYTES = 32; // 256-bit AES key

    private final URI endpointUrl;
    private final BearerTokenService tokenService;
    private final HttpClient client;

    /**
     * Create a CipherTrust Manager KMS instance.
     *
     * @param endpointUrl base URL of CipherTrust Manager instance
     * @param tokenService bearer token service for authentication
     * @param timeout HTTP request timeout
     * @param tlsConfigurator TLS configuration for HTTP client
     */
    public CipherTrustKms(URI endpointUrl,
                          BearerTokenService tokenService,
                          Duration timeout,
                          UnaryOperator<HttpClient.Builder> tlsConfigurator) {
        Objects.requireNonNull(endpointUrl, "endpointUrl cannot be null");
        Objects.requireNonNull(tokenService, "tokenService cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        Objects.requireNonNull(tlsConfigurator, "tlsConfigurator cannot be null");

        this.endpointUrl = endpointUrl;
        this.tokenService = tokenService;
        this.client = createClient(timeout, tlsConfigurator);
    }

    private HttpClient createClient(Duration timeout, UnaryOperator<HttpClient.Builder> tlsConfigurator) {
        return tlsConfigurator.apply(HttpClient.newBuilder())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public CompletionStage<DekPair<CipherTrustEdek>> generateDekPair(String kekRef) {
        LOGGER.atDebug()
                .addKeyValue("kekRef", kekRef)
                .log("generating DEK pair");

        // Step 1: Generate random DEK bytes
        return generateRandomBytes(DEK_SIZE_BYTES)
                .thenCompose(plaintextDek -> {
                    // Step 2: Encrypt DEK with KEK
                    return encryptDek(kekRef, plaintextDek)
                            .thenApply(edek -> {
                                // Step 3: Create DekPair
                                SecretKey secretKey = DestroyableRawSecretKey.takeOwnershipOf(plaintextDek, AES_KEY_ALGO);
                                LOGGER.atDebug()
                                        .addKeyValue("kekRef", kekRef)
                                        .log("DEK pair generated successfully");
                                return new DekPair<>(edek, secretKey);
                            });
                });
    }

    private CompletionStage<byte[]> generateRandomBytes(int numBytes) {
        URI randomUri = endpointUrl.resolve("/api/v1/vault/random?bytes=" + numBytes);

        return tokenService.getBearerToken()
                .thenCompose(token -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(randomUri)
                            .header("Authorization", "Bearer " + token.token())
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                })
                .thenApply(response -> checkResponseStatus(response, "random generation"))
                .thenApply(HttpResponse::body)
                .thenApply(this::parseRandomResponse)
                .thenApply(RandomResponse::bytes);
    }

    private CompletionStage<CipherTrustEdek> encryptDek(String kekRef, byte[] plaintextDek) {
        URI encryptUri = endpointUrl.resolve("/api/v1/crypto/encrypt");
        EncryptRequest encryptRequest = new EncryptRequest(kekRef, plaintextDek);

        return tokenService.getBearerToken()
                .thenCompose(token -> {
                    try {
                        String requestBody = OBJECT_MAPPER.writeValueAsString(encryptRequest);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(encryptUri)
                                .header("Authorization", "Bearer " + token.token())
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                                .build();

                        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException("Failed to serialize encrypt request", e);
                    }
                })
                .thenApply(response -> checkResponseStatus(response, "encryption", kekRef))
                .thenApply(HttpResponse::body)
                .thenApply(this::parseEncryptResponse)
                .thenApply(encryptResponse -> new CipherTrustEdek(
                        encryptResponse.id(),
                        encryptResponse.ciphertext(),
                        encryptResponse.tag(),
                        encryptResponse.version(),
                        encryptResponse.mode(),
                        encryptResponse.iv()));
    }

    @Override
    public CompletionStage<SecretKey> decryptEdek(CipherTrustEdek edek) {
        LOGGER.atDebug()
                .addKeyValue("kekRef", edek.id())
                .log("decrypting EDEK");

        URI decryptUri = endpointUrl.resolve("/api/v1/crypto/decrypt");
        DecryptRequest decryptRequest = new DecryptRequest(
                edek.ciphertext(),
                edek.tag(),
                edek.id(),
                edek.version(),
                edek.mode(),
                edek.iv());

        return tokenService.getBearerToken()
                .thenCompose(token -> {
                    try {
                        String requestBody = OBJECT_MAPPER.writeValueAsString(decryptRequest);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(decryptUri)
                                .header("Authorization", "Bearer " + token.token())
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                                .build();

                        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException("Failed to serialize decrypt request", e);
                    }
                })
                .thenApply(response -> checkResponseStatus(response, "decryption", edek.id()))
                .thenApply(HttpResponse::body)
                .thenApply(this::parseDecryptResponse)
                .thenApply(decryptResponse -> {
                    byte[] plaintextDek = decryptResponse.plaintext();
                    LOGGER.atDebug()
                            .addKeyValue("kekRef", edek.id())
                            .log("EDEK decrypted successfully");
                    return DestroyableRawSecretKey.takeOwnershipOf(plaintextDek, AES_KEY_ALGO);
                });
    }

    @Override
    public CompletionStage<String> resolveAlias(String alias) {
        LOGGER.atDebug()
                .addKeyValue("alias", alias)
                .log("resolving key alias");

        URI keysUri = endpointUrl.resolve("/api/v1/vault/keys2?name=" + alias);

        return tokenService.getBearerToken()
                .thenCompose(token -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(keysUri)
                            .header("Authorization", "Bearer " + token.token())
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                })
                .thenApply(response -> checkResponseStatus(response, "alias resolution", alias))
                .thenApply(HttpResponse::body)
                .thenApply(this::parseGetKeysResponse)
                .thenApply(keysResponse -> {
                    if (keysResponse.total() == 0 || keysResponse.resources() == null || keysResponse.resources().isEmpty()) {
                        LOGGER.atWarn()
                                .addKeyValue("alias", alias)
                                .log("key alias not found");
                        throw new UnknownAliasException("alias '%s' not found".formatted(alias));
                    }
                    String keyId = keysResponse.resources().get(0).id();
                    LOGGER.atDebug()
                            .addKeyValue("alias", alias)
                            .addKeyValue("keyId", keyId)
                            .log("alias resolved successfully");
                    return keyId;
                });
    }

    @Override
    public Serde<CipherTrustEdek> edekSerde() {
        return CipherTrustEdekSerde.instance();
    }

    private HttpResponse<byte[]> checkResponseStatus(HttpResponse<byte[]> response, String operation) {
        return checkResponseStatus(response, operation, null);
    }

    private HttpResponse<byte[]> checkResponseStatus(HttpResponse<byte[]> response, String operation, String keyRef) {
        int statusCode = response.statusCode();

        if (statusCode == 404) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            LOGGER.atWarn()
                    .addKeyValue("operation", operation)
                    .addKeyValue("keyRef", keyRef)
                    .addKeyValue("statusCode", statusCode)
                    .addKeyValue("responseBody", body)
                    .log("key not found");

            if (keyRef != null) {
                throw new UnknownKeyException("key '%s' not found".formatted(keyRef));
            }
            else {
                throw new KmsException("%s failed: resource not found".formatted(operation));
            }
        }
        else if (statusCode != 200) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            LOGGER.atWarn()
                    .addKeyValue("operation", operation)
                    .addKeyValue("keyRef", keyRef)
                    .addKeyValue("statusCode", statusCode)
                    .addKeyValue("responseBody", body)
                    .log("{} failed", operation);
            throw new KmsException("%s failed with HTTP %d".formatted(operation, statusCode));
        }

        return response;
    }

    private RandomResponse parseRandomResponse(byte[] bytes) {
        return parseResponse(bytes, RandomResponse.class, "random response");
    }

    private EncryptResponse parseEncryptResponse(byte[] bytes) {
        return parseResponse(bytes, EncryptResponse.class, "encrypt response");
    }

    private DecryptResponse parseDecryptResponse(byte[] bytes) {
        DecryptResponse response = parseResponse(bytes, DecryptResponse.class, "decrypt response");
        // Zero out the response body bytes to avoid leaving plaintext in memory
        Arrays.fill(bytes, (byte) 0);
        return response;
    }

    private GetKeysResponse parseGetKeysResponse(byte[] bytes) {
        return parseResponse(bytes, GetKeysResponse.class, "get keys response");
    }

    private <T> T parseResponse(byte[] bytes, Class<T> valueType, String description) {
        try {
            return OBJECT_MAPPER.readValue(bytes, valueType);
        }
        catch (IOException e) {
            String responseBody = new String(bytes, StandardCharsets.UTF_8);
            LOGGER.atWarn()
                    .setCause(e)
                    .addKeyValue("responseBody", responseBody)
                    .log("failed to parse {}", description);
            throw new UncheckedIOException("Failed to parse " + description, e);
        }
    }

    private <T> T parseResponse(byte[] bytes, TypeReference<T> typeReference, String description) {
        try {
            return OBJECT_MAPPER.readValue(bytes, typeReference);
        }
        catch (IOException e) {
            String responseBody = new String(bytes, StandardCharsets.UTF_8);
            LOGGER.atWarn()
                    .setCause(e)
                    .addKeyValue("responseBody", responseBody)
                    .log("failed to parse {}", description);
            throw new UncheckedIOException("Failed to parse " + description, e);
        }
    }
}
