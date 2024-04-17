/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms;

import java.nio.ByteBuffer;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import io.kroxylicious.kms.provider.aws.kms.config.Config;
import io.kroxylicious.kms.service.DekPair;
import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.SecretKeyUtils;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.kms.service.UnknownKeyException;
import io.kroxylicious.proxy.config.secret.InlinePassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration tests for AWS KMS.
 */
class AwsKmsKmsIT {

    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:0.11.3");

    private LocalStackContainer localStackContainer;
    private AwsKmsKms service;

    @BeforeEach
    void beforeEach() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable()).withFailMessage("docker unavailable").isTrue();
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
        var config = new Config(localStackContainer.getEndpoint(),
                new InlinePassword(localStackContainer.getAccessKey()),
                new InlinePassword(localStackContainer.getSecretKey()),
                localStackContainer.getRegion(), null);
        service = new AwsKmsKmsService().buildKms(config);
    }

    @AfterEach
    void afterEach() {
        if (localStackContainer != null) {
            localStackContainer.close();
        }
    }

    @Test
    void resolveKeyByName() {
        var keyName = "mykey";
        createKek(keyName);
        var resolved = service.resolveAlias(keyName);
        assertThat(resolved)
                .succeedsWithin(Duration.ofSeconds(5))
                .isEqualTo(keyName);
    }

    @Test
    void resolveWithUnknownKey() {
        var keyName = "unknown";
        var resolved = service.resolveAlias(keyName);
        assertThat(resolved)
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .withCauseInstanceOf(UnknownAliasException.class);
    }

    @Test
    void generatedEncryptedDekDecryptsBackToPlain() {
        String key = "mykey";
        createKek(key);

        var pairStage = service.generateDekPair(key);
        assertThat(pairStage).succeedsWithin(Duration.ofSeconds(5));
        var pair = pairStage.toCompletableFuture().join();

        var decryptedDekStage = service.decryptEdek(pair.edek());
        assertThat(decryptedDekStage)
                .succeedsWithin(Duration.ofSeconds(5))
                .matches(sk -> SecretKeyUtils.same((DestroyableRawSecretKey) sk, (DestroyableRawSecretKey) pair.dek()));
    }

    @Test
    void decryptDekAfterRotate() {
        var key = "mykey";
        var data = createKek(key);
        var originalVersion = data.latestVersion();

        var pairStage = service.generateDekPair(key);
        assertThat(pairStage).succeedsWithin(Duration.ofSeconds(5));
        var pair = pairStage.toCompletableFuture().join();

        var updated = rotateKek(data.name());
        var versionAfterRotate = updated.latestVersion();
        assertThat(versionAfterRotate).isGreaterThan(originalVersion);

        var decryptedDekStage = service.decryptEdek(pair.edek());
        assertThat(decryptedDekStage)
                .succeedsWithin(Duration.ofSeconds(5))
                .matches(sk -> SecretKeyUtils.same((DestroyableRawSecretKey) sk, (DestroyableRawSecretKey) pair.dek()));
    }

    @Test
    void generatedDekPairWithUnknownKey() {
        var pairStage = service.generateDekPair("unknown");
        assertThat(pairStage)
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .withCauseInstanceOf(UnknownKeyException.class);
    }

    @Test
    void decryptEdekWithUnknownKey() {
        var secretKeyStage = service.decryptEdek(new AwsKmsEdek("unknown", new byte[]{ 1 }));
        assertThat(secretKeyStage)
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .withCauseInstanceOf(UnknownKeyException.class);
    }

    @Test
    void edekSerdeRoundTrip() {
        var key = "mykey";
        createKek(key);

        var pairStage = service.generateDekPair(key);
        assertThat(pairStage).succeedsWithin(Duration.ofSeconds(5));
        var pair = pairStage.toCompletableFuture().join();
        assertThat(pair).extracting(DekPair::edek).isNotNull();

        var edek = pair.edek();
        var serde = service.edekSerde();
        var buf = ByteBuffer.allocate(serde.sizeOf(edek));
        serde.serialize(edek, buf);
        buf.flip();
        var output = serde.deserialize(buf);
        assertThat(output).isEqualTo(edek);
    }

    private AwsKmsResponse.ReadKeyData createKek(String keyId) {
        throw new UnsupportedOperationException();
        // return localStackContainer.runVaultCommand(new TypeReference<>() {
        // }, "vault", "write", "-f", "transit/keys/%s".formatted(keyId));
    }

    private AwsKmsResponse.ReadKeyData rotateKek(String keyId) {
        throw new UnsupportedOperationException();
        // return localStackContainer.runVaultCommand(new TypeReference<>() {
        // }, "vault", "write", "-f", "transit/keys/%s/rotate".formatted(keyId));
    }

}
