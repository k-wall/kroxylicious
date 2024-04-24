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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Objects;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.TestKekManager;
import io.kroxylicious.kms.service.UnknownAliasException;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZonedDateTime.now;

public class AwsKmsTestKmsFacade extends AbstractAwsKmsTestKmsFacade {
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:0.11.3");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String APPLICATION_X_AMZ_JSON_1_1 = "application/x-amz-json-1.1";
    private static final TypeReference<CreateKeyResponse> CREATE_KEY_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<DescribeKeyResponse> DESCRIBE_KEY_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<ScheduleKeyDeletionResponse> SCHEDULE_KEY_DELETION_TYPE_REF = new TypeReference<>() {
    };
    private final HttpClient client = HttpClient.newHttpClient();
    private LocalStackContainer localStackContainer;

    @Override
    public boolean isAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Override
    @SuppressWarnings("resource")
    public void startKms() {
        localStackContainer = new LocalStackContainer(LOCALSTACK_IMAGE) {
            @Override
            public LocalStackContainer withFileSystemBind(String hostPath, String containerPath) {
                // Workaround problem with LocalStackContainer on the Mac that manifests
                // under both Docker and Podman when the Container mounts the
                // docker.sock. It turns out that the mount is required by the Lambda Provider
                // so skipping it has no consequence for our use-case.
                // https://docs.localstack.cloud/getting-started/installation/#docker
                // TODO raise testcontainer issue
                return this;
            }
        }.withServices(LocalStackContainer.Service.KMS);

        localStackContainer.start();
    }

    @Override
    public void stopKms() {
        if (localStackContainer != null) {
            localStackContainer.close();
        }
    }

    private static <T> T decodeJson(TypeReference<T> valueTypeRef, byte[] bytes) {
        try {
            return OBJECT_MAPPER.readValue(bytes, valueTypeRef);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @NonNull
    protected URI getAwsUrl() {
        return localStackContainer.getEndpointOverride(LocalStackContainer.Service.KMS);
    }

    @Override
    protected String getRegion() {
        return localStackContainer.getRegion();
    }

    @Override
    protected String getSecretKey() {
        return localStackContainer.getSecretKey();
    }

    @Override
    protected String getAccessKey() {
        return localStackContainer.getAccessKey();
    }

    @Override
    public TestKekManager getTestKekManager() {
        return new AwsKmsTestKekManager();
    }

    class AwsKmsTestKekManager implements TestKekManager {
        @Override
        public void generateKek(String alias) {
            Objects.requireNonNull(alias);

            if (exists(alias)) {
                throw new AlreadyExistsException(alias);
            }
            else {
                create(alias);
            }
        }

        @Override
        public void rotateKek(String alias) {
            // Rotate is not supported on localstack
            // https://docs.localstack.cloud/references/coverage/coverage_kms/
            throw new UnsupportedOperationException();

        }

        @Override
        public void deleteKek(String alias) {
            if (!exists(alias)) {
                throw new UnknownAliasException(alias);
            }
            else {
                delete(alias);
            }
        }

        @Override
        public boolean exists(String alias) {
            try {
                read(alias);
                return true;
            }
            catch (UnknownAliasException uae) {
                return false;
            }
        }

        private void create(String keyId) {

            var keyRequest = createRequest(new CreateKeyRequest("key for alias : " + keyId));

            var createKeyResponse = sendRequest(keyId, keyRequest, CREATE_KEY_TYPE_REF);

            var aliasRequest = createRequest(new CreateAliasRequest(createKeyResponse.keyMetadata().keyId(), "alias/" + keyId));
            sendRequestExpectingNoResponse(aliasRequest);
            // TODO clean up key if alias create fails?
        }

        private DescribeKeyResponse read(String alias) {
            var request = createRequest(new DescribeKeyRequest("alias/" + alias));
            return sendRequest(alias, request, DESCRIBE_KEY_TYPE_REF);
        }

        private void delete(String alias) {

            var key = read(alias);
            var keyId = key.keyMetadata().keyId();
            var scheduleDeleteRequest = createRequest(new ScheduleKeyDeletionRequest(keyId, 7 /* Minimum allowed */));

            sendRequest(keyId, scheduleDeleteRequest, SCHEDULE_KEY_DELETION_TYPE_REF);

            var deleteAliasRequest = createRequest(new DeleteAliasRequest("alias/" + alias));
            sendRequestExpectingNoResponse(deleteAliasRequest);
        }

    }

    private HttpRequest createRequest(Object request) {

        var body = getBody(request).getBytes(UTF_8);
        var date = DATE_TIME_FORMATTER.format(now(ZoneOffset.UTC));

        var headers = new HashMap<String, String>();
        AmazonRequestSignatureV4Utils.calculateAuthorizationHeaders(
                "POST",
                getAwsUrl().getHost(),
                null,
                null,
                headers,
                body,
                date,
                getAccessKey(),
                getSecretKey(),
                getRegion(),
                "kms");

        var builder = HttpRequest.newBuilder().uri(getAwsUrl());
        headers.entrySet().stream().filter(e -> !e.getKey().equals("Host")).forEach(e -> builder.header(e.getKey(), e.getValue()));

        return builder
                .header("Content-Type", APPLICATION_X_AMZ_JSON_1_1)
                .header("X-Amz-Target", getTarget(request.getClass()))
                .POST(BodyPublishers.ofByteArray(body))
                .build();
    }

    @NonNull
    private static String getTarget(Class<?> aClass) {
        if (aClass == DescribeKeyRequest.class) {
            return "TrentService.DescribeKey";
        }
        else if (aClass == CreateKeyRequest.class) {
            return "TrentService.CreateKey";
        }
        else if (aClass == CreateAliasRequest.class) {
            return "TrentService.CreateAlias";
        }
        else if (aClass == DeleteAliasRequest.class) {
            return "TrentService.DeleteAlias";
        }
        else if (aClass == ScheduleKeyDeletionRequest.class) {
            return "TrentService.ScheduleKeyDeletion";
        }
        else {
            throw new IllegalArgumentException("target not known for class " + aClass);
        }
    }

    private <R> R sendRequest(String key, HttpRequest request, TypeReference<R> valueTypeRef) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                var er = decodeJson(new TypeReference<ErrorResponse>() {
                }, response.body());
                if (er.type().equalsIgnoreCase("NotFoundException")) {
                    throw new UnknownAliasException(key);
                }
                throw new IllegalStateException("unexpected response %s (%s) for request: %s".formatted(response.statusCode(), er, request.uri()));
            }
            byte[] body = response
                    .body();
            return decodeJson(valueTypeRef, body);
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

    private void sendRequestExpectingNoResponse(HttpRequest request) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Unexpected response : %d to request %s".formatted(response.statusCode(), request.uri()));
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Request to %s failed".formatted(request), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String getBody(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to create request body", e);
        }
    }

}
