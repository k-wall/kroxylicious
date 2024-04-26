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
import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.Serde;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.kms.service.UnknownKeyException;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZonedDateTime.now;

/**
 * An implementation of the KMS interface backed by a remote instance of AWS KMS.
 *
 * TODO KW fix me!!!
 * - get rid of type latter in getTarget
 * - sort of the AWS request signing
 * - work out/fix why the localstack container is taking 12seconds (vs 1 second for Vault!)
 */
public class AwsKmsKms implements Kms<String, AwsKmsEdek> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AES_KEY_ALGO = "AES";
    private static final TypeReference<DescribeKeyResponse> DESCRIBE_KEY_RESPONSE_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<GenerateDataKeyResponse> GENERATE_DATA_KEY_RESPONSE_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<DecryptResponse> DECRYPT_RESPONSE_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<ErrorResponse> ERROR_RESPONSE_TYPE_REF = new TypeReference<>() {
    };

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final Duration timeout;
    private final HttpClient client;

    /**
     * The AWS KMS url.
     */
    private final URI awsUrl;

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
     * @see <a href="https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html">https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html</a>
     */
    @NonNull
    @Override
    public CompletionStage<DekPair<AwsKmsEdek>> generateDekPair(@NonNull String kekRef) {
        var request = createRequest(new GenerateDataKeyRequest(kekRef, "AES_256"));
        return sendAsync(kekRef, request, GENERATE_DATA_KEY_RESPONSE_TYPE_REF, UnknownKeyException::new)
                .thenApply(response -> {
                    var key = DestroyableRawSecretKey.takeOwnershipOf(response.plaintext(), AES_KEY_ALGO);
                    return new DekPair<>(new AwsKmsEdek(kekRef, response.ciphertextBlob()), key);
                });
    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html">https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html</a>
     */
    @NonNull
    @Override
    public CompletionStage<SecretKey> decryptEdek(@NonNull AwsKmsEdek edek) {
        var request = createRequest(new DecryptRequest(edek.kekRef(), edek.edek()));
        return sendAsync(edek.kekRef(), request, DECRYPT_RESPONSE_TYPE_REF, UnknownKeyException::new)
                .thenApply(response -> DestroyableRawSecretKey.takeOwnershipOf(response.plaintext(), AES_KEY_ALGO));
    }

    /**
     * {@inheritDoc}
     * <br/>
     * @see <a href="https://docs.aws.amazon.com/kms/latest/APIReference/API_DescribeKey.html">https://docs.aws.amazon.com/kms/latest/APIReference/API_DescribeKey.html</a>
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
            var error = decodeJson(ERROR_RESPONSE_TYPE_REF, response.body());
            throw new KmsException("fail to retrieve key '%s', HTTP status code %d, AWS error: %s".formatted(key, response.statusCode(), error));
        }
        return response;
    }

    @NonNull
    @Override
    public Serde<AwsKmsEdek> edekSerde() {
        return new AwsKmsEdekSerde();
    }

    @NonNull
    private URI getAwsUrl() {
        return awsUrl;
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
        else if (clazz == GenerateDataKeyRequest.class) {
            return "TrentService.GenerateDataKey";
        }
        else if (clazz == DecryptRequest.class) {
            return "TrentService.Decrypt";
        }
        else {
            throw new IllegalArgumentException("target not known for class " + clazz);
        }
    }
}
