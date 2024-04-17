/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.kms.provider.aws.kms.AwsKmsResponse.DataKeyData;
import io.kroxylicious.kms.provider.aws.kms.AwsKmsResponse.DecryptData;
import io.kroxylicious.kms.provider.aws.kms.AwsKmsResponse.ReadKeyData;
import io.kroxylicious.kms.service.DekPair;
import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.Serde;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.kms.service.UnknownKeyException;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An implementation of the KMS interface backed by a remote instance of AWS KMS.
 */
public class AwsKmsKms implements Kms<String, AwsKmsEdek> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AES_KEY_ALGO = "AES";
    private static final TypeReference<AwsKmsResponse<DataKeyData>> DATA_KEY_DATA_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<AwsKmsResponse<ReadKeyData>> READ_KEY_DATA_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<AwsKmsResponse<DecryptData>> DECRYPT_DATA_TYPE_REF = new TypeReference<>() {
    };
    private final String secretKey;
    private final Duration timeout;
    private final HttpClient vaultClient;

    /**
     * The vault url which will include the path to the transit engine.
     */
    private final URI awsUrl;
    private final String vaultToken;

    AwsKmsKms(URI awsUrl, String accessKey, String secretKey, Duration timeout, SSLContext sslContext) {
        this.awsUrl = awsUrl;
        this.vaultToken = accessKey;
        this.secretKey = secretKey;
        this.timeout = timeout;
        vaultClient = createClient(sslContext);
    }

    @VisibleForTesting
    HttpClient createClient(SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://developer.hashicorp.com/vault/api-docs/secret/transit#generate-data-key">https://developer.hashicorp.com/vault/api-docs/secret/transit#generate-data-key</a>
     */
    @NonNull
    @Override
    public CompletionStage<DekPair<AwsKmsEdek>> generateDekPair(@NonNull String kekRef) {

        var request = createVaultRequest()
                .uri(awsUrl.resolve("datakey/plaintext/%s".formatted(encode(kekRef, UTF_8))))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return sendAsync(kekRef, request, DATA_KEY_DATA_TYPE_REF, UnknownKeyException::new)
                .thenApply(data -> {
                    var secretKey = DestroyableRawSecretKey.takeOwnershipOf(data.plaintext(), AES_KEY_ALGO);
                    return new DekPair<>(new AwsKmsEdek(kekRef, data.ciphertext().getBytes(UTF_8)), secretKey);
                });

    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://developer.hashicorp.com/vault/api-docs/secret/transit#decrypt">https://developer.hashicorp.com/vault/api-docs/secret/transit#decrypt</a>
     */
    @NonNull
    @Override
    public CompletionStage<SecretKey> decryptEdek(@NonNull AwsKmsEdek edek) {

        var body = createDecryptPostBody(edek);

        var request = createVaultRequest()
                .uri(awsUrl.resolve("decrypt/%s".formatted(encode(edek.kekRef(), UTF_8))))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return sendAsync(edek.kekRef(), request, DECRYPT_DATA_TYPE_REF, UnknownKeyException::new)
                .thenApply(data -> DestroyableRawSecretKey.takeOwnershipOf(data.plaintext(), AES_KEY_ALGO));
    }

    private String createDecryptPostBody(@NonNull AwsKmsEdek edek) {
        var map = Map.of("ciphertext", new String(edek.edek(), UTF_8));

        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        }
        catch (JsonProcessingException e) {
            throw new KmsException("Failed to build request body for %s".formatted(edek.kekRef()));
        }
    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://developer.hashicorp.com/vault/api-docs/secret/transit#read-key">https://developer.hashicorp.com/vault/api-docs/secret/transit#read-key</a>
     */
    @NonNull
    @Override
    public CompletableFuture<String> resolveAlias(@NonNull String alias) {

        var request = createVaultRequest()
                .uri(awsUrl.resolve("keys/%s".formatted(encode(alias, UTF_8))))
                .build();
        return sendAsync(alias, request, READ_KEY_DATA_TYPE_REF, UnknownAliasException::new)
                .thenApply(ReadKeyData::name);
    }

    private <T> CompletableFuture<T> sendAsync(@NonNull String key, HttpRequest request,
                                               TypeReference<AwsKmsResponse<T>> valueTypeRef,
                                               Function<String, KmsException> exception) {
        return vaultClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> checkResponseStatus(key, response, exception))
                .thenApply(HttpResponse::body)
                .thenApply(bytes -> decodeJson(valueTypeRef, bytes))
                .thenApply(AwsKmsResponse::data);
    }

    private static <T> AwsKmsResponse<T> decodeJson(TypeReference<AwsKmsResponse<T>> valueTypeRef, byte[] bytes) {
        try {
            AwsKmsResponse<T> result = OBJECT_MAPPER.readValue(bytes, valueTypeRef);
            Arrays.fill(bytes, (byte) 0);
            return result;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @NonNull
    private static HttpResponse<byte[]> checkResponseStatus(@NonNull String key,
                                                            HttpResponse<byte[]> response,
                                                            Function<String, KmsException> notFound) {
        if (response.statusCode() == 404 || response.statusCode() == 400) {
            throw notFound.apply("key '%s' is not found.".formatted(key));
        }
        else if (response.statusCode() != 200) {
            throw new KmsException("fail to retrieve key '%s', HTTP status code %d.".formatted(key, response.statusCode()));
        }
        return response;
    }

    @NonNull
    @Override
    public Serde<AwsKmsEdek> edekSerde() {
        return new AwsKmsEdekSerde();
    }

    @VisibleForTesting
    HttpRequest.Builder createVaultRequest() {
        return HttpRequest.newBuilder()
                .timeout(timeout)
                .header("X-Vault-Token", vaultToken)
                .header("Accept", "application/json");
    }

    @VisibleForTesting
    URI getVaultTransitEngineUri() {
        return awsUrl;
    }
}
