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
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.kms.service.DekPair;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.Serde;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZonedDateTime.now;

/**
 * An implementation of the KMS interface backed by a remote instance of AWS KMS.
 */
public class AwsKmsKms implements Kms<String, AwsKmsEdek> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AES_KEY_ALGO = "AES";
    private static final TypeReference<DescribeKeyResponse> DESCRIBE_KEY_RESPONSE_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<ErrorResponse> ERROR_RESPONSE_TYPE_REF = new TypeReference<>() {
    };

    private final String secretKey;
    private final String region;
    private final Duration timeout;
    private final HttpClient client;

    /**
     * The vault url which will include the path to the transit engine.
     */
    private final URI awsUrl;
    private final String accessKey;

    AwsKmsKms(URI awsUrl, String accessKey, String secretKey, String region, Duration timeout, SSLContext sslContext) {
        Objects.requireNonNull(awsUrl);
        Objects.requireNonNull(accessKey);
        Objects.requireNonNull(secretKey);
        Objects.requireNonNull(region);
        this.awsUrl = awsUrl;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.timeout = timeout;
        client = createClient(sslContext);
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
        throw new UnsupportedOperationException();
        //
        // var request = createVaultRequest()
        // .uri(awsUrl.resolve("datakey/plaintext/%s".formatted(encode(kekRef, UTF_8))))
        // .POST(HttpRequest.BodyPublishers.noBody())
        // .build();
        //
        // return sendAsync(kekRef, request, DATA_KEY_DATA_TYPE_REF, UnknownKeyException::new)
        // .thenApply(data -> {
        // var secretKey = DestroyableRawSecretKey.takeOwnershipOf(data.plaintext(), AES_KEY_ALGO);
        // return new DekPair<>(new AwsKmsEdek(kekRef, data.ciphertext().getBytes(UTF_8)), secretKey);
        // });

    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://developer.hashicorp.com/vault/api-docs/secret/transit#decrypt">https://developer.hashicorp.com/vault/api-docs/secret/transit#decrypt</a>
     */
    @NonNull
    @Override
    public CompletionStage<SecretKey> decryptEdek(@NonNull AwsKmsEdek edek) {
        throw new UnsupportedOperationException();
        //
        // var body = createDecryptPostBody(edek);
        //
        // var request = createVaultRequest()
        // .uri(awsUrl.resolve("decrypt/%s".formatted(encode(edek.kekRef(), UTF_8))))
        // .POST(HttpRequest.BodyPublishers.ofString(body))
        // .build();
        //
        // return sendAsync(edek.kekRef(), request, DECRYPT_DATA_TYPE_REF, UnknownKeyException::new)
        // .thenApply(data -> DestroyableRawSecretKey.takeOwnershipOf(data.plaintext(), AES_KEY_ALGO));
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
        var request = createRequest(new DescribeKeyRequest("alias/" + alias));
        return sendAsync(alias, request, DESCRIBE_KEY_RESPONSE_TYPE_REF, UnknownAliasException::new)
                .thenApply(DescribeKeyResponse::keyMetadata)
                .thenApply(KeyMetadata::keyId);
    }

    private <T> CompletableFuture<T> sendAsync(@NonNull String key, HttpRequest request,
                                               TypeReference<T> valueTypeRef,
                                               Function<String, KmsException> exception) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> checkResponseStatus(key, response, exception))
                .thenApply(HttpResponse::body)
                .thenApply(bytes -> decodeJson(valueTypeRef, bytes));
    }

    private static <T> T decodeJson(TypeReference<T> valueTypeRef, byte[] bytes) {
        try {
            return OBJECT_MAPPER.readValue(bytes, valueTypeRef);
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
                .header("X-Vault-Token", accessKey)
                .header("Accept", "application/json");
    }

    @NonNull
    private URI getAwsUrl() {
        return awsUrl;
    }

    private <R> R sendRequest(String key, HttpRequest request, TypeReference<R> valueTypeRef) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                var er = decodeJson(ERROR_RESPONSE_TYPE_REF, response.body());
                if (er.type().equalsIgnoreCase("NotFoundException")) {
                    throw new UnknownAliasException(key);
                }
                throw new IllegalStateException("unexpected response %s (%s) for request: %s".formatted(response.statusCode(), er, request.uri()));
            }
            return decodeJson(valueTypeRef, response.body());
        }
        catch (IOException e) {
            if (e.getCause() instanceof KmsException ke) {
                throw ke;
            }
            throw new UncheckedIOException("Request to %s failed".formatted(request), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during REST API call : %s".formatted(request.uri()), e);
        }
    }

    private HttpRequest createRequest(Object request) {

        var body = getBody(request).getBytes(UTF_8);
        var date = AmazonRequestSignatureV4Utils.DATE_TIME_FORMATTER.format(now(ZoneOffset.UTC));

        var headers = new HashMap<String, String>();
        AmazonRequestSignatureV4Utils.calculateAuthorizationHeaders(
                "POST",
                getAwsUrl().getHost(),
                null,
                null,
                headers,
                body,
                date,
                accessKey,
                secretKey,
                region,
                "kms");

        var builder = HttpRequest.newBuilder().uri(getAwsUrl());
        headers.entrySet().stream().filter(e -> !e.getKey().equals("Host")).forEach(e -> builder.header(e.getKey(), e.getValue()));

        return builder
                .header("Content-Type", AmazonRequestSignatureV4Utils.APPLICATION_X_AMZ_JSON_1_1)
                .header("X-Amz-Target", getTarget(request.getClass()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private String getBody(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to create request body", e);
        }
    }

    private static String getTarget(Class<?> clazz) {
        if (clazz == DescribeKeyRequest.class) {
            return "TrentService.DescribeKey";
        }
        // else if (clazz == CreateKeyRequest.class) {
        // return "TrentService.CreateKey";
        // }
        // else if (clazz == CreateAliasRequest.class) {
        // return "TrentService.CreateAlias";
        // }
        // else if (clazz == UpdateAliasRequest.class) {
        // return "TrentService.UpdateAlias";
        // }
        // else if (clazz == DeleteAliasRequest.class) {
        // return "TrentService.DeleteAlias";
        // }
        // else if (clazz == ScheduleKeyDeletionRequest.class) {
        // return "TrentService.ScheduleKeyDeletion";
        // }
        else {
            throw new IllegalArgumentException("target not known for class " + clazz);
        }
    }

}
