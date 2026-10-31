/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.it;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

import io.kroxylicious.proxy.config.ConfigurationBuilder;
import io.kroxylicious.proxy.config.ProxyProtocolMode;
import io.kroxylicious.testing.certificate.CertificateGenerator;
import io.kroxylicious.testing.integration.Request;
import io.kroxylicious.testing.integration.ResponsePayload;
import io.kroxylicious.testing.integration.client.KafkaClient;

import static io.kroxylicious.testing.integration.client.KafkaClient.TRUST_ALL_CLIENT_SSL_CONTEXT;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.DEFAULT_CLUSTER_DEF_NAME;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.DEFAULT_CLUSTER_TARGET;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.OS_ASSIGNED_BOOTSTRAP;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.baseConfigurationBuilder;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.defaultPortIdentifiesNodeGatewayBuilder;
import static io.kroxylicious.testing.integration.tester.KroxyliciousTesters.mockKafkaKroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for per-gateway HAProxy PROXY protocol support.
 * Covers both plain-channel and TLS-channel cases for REQUIRED and FORBIDDEN modes.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ProxyProtocolIT {

    private static final CertificateGenerator.Keys TLS_KEYS = CertificateGenerator.generate();

    // Factory method — HAProxyMessage is ref-counted; the encoder releases it after writing,
    // so each test must get a fresh instance rather than sharing a static one.
    private static HAProxyMessage proxyMessage() {
        return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "192.168.1.100", "127.0.0.1", 54321, 9092);
    }

    // ---- plain channel ----

    @Test
    void requiredMode_plainChannel_withProxyHeader_succeeds() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> plainConfig(bootstrap, ProxyProtocolMode.REQUIRED))) {
            tester.addMockResponseForApiKey(new ResponsePayload(ApiKeys.API_VERSIONS,
                    ApiVersionsRequestData.HIGHEST_SUPPORTED_VERSION, new ApiVersionsResponseData()));
            var parts = tester.getBootstrapAddress().split(":", 2);
            try (var client = KafkaClient.withProxyProtocol(parts[0], Integer.parseInt(parts[1]), proxyMessage())) {

                // When
                var response = client.get(apiVersionsRequest());

                // Then
                assertThat(response).succeedsWithin(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void requiredMode_plainChannel_withoutProxyHeader_rejects() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> plainConfig(bootstrap, ProxyProtocolMode.REQUIRED));
                var client = tester.simpleTestClient()) {

            // When
            var response = client.get(apiVersionsRequest());

            // Then
            assertThat(response).failsWithin(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forbiddenMode_plainChannel_withoutProxyHeader_succeeds() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> plainConfig(bootstrap, ProxyProtocolMode.FORBIDDEN));
                var client = tester.simpleTestClient()) {
            tester.addMockResponseForApiKey(new ResponsePayload(ApiKeys.API_VERSIONS,
                    ApiVersionsRequestData.HIGHEST_SUPPORTED_VERSION, new ApiVersionsResponseData()));

            // When
            var response = client.get(apiVersionsRequest());

            // Then
            assertThat(response).succeedsWithin(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forbiddenMode_plainChannel_withProxyHeader_rejects() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> plainConfig(bootstrap, ProxyProtocolMode.FORBIDDEN))) {
            var parts = tester.getBootstrapAddress().split(":", 2);
            try (var client = KafkaClient.withProxyProtocol(parts[0], Integer.parseInt(parts[1]), proxyMessage())) {

                // When
                var response = client.get(apiVersionsRequest());

                // Then
                assertThat(response).failsWithin(5, TimeUnit.SECONDS);
            }
        }
    }

    // ---- TLS channel ----

    @Test
    void requiredMode_tlsChannel_withProxyHeader_succeeds() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> tlsConfig(bootstrap, ProxyProtocolMode.REQUIRED))) {
            tester.addMockResponseForApiKey(new ResponsePayload(ApiKeys.API_VERSIONS,
                    ApiVersionsRequestData.HIGHEST_SUPPORTED_VERSION, new ApiVersionsResponseData()));
            var parts = tester.getBootstrapAddress().split(":", 2);
            try (var client = KafkaClient.withProxyProtocol(parts[0], Integer.parseInt(parts[1]),
                    proxyMessage(), TRUST_ALL_CLIENT_SSL_CONTEXT)) {

                // When
                var response = client.get(apiVersionsRequest());

                // Then
                assertThat(response).succeedsWithin(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void requiredMode_tlsChannel_withoutProxyHeader_rejects() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> tlsConfig(bootstrap, ProxyProtocolMode.REQUIRED));
                var client = tester.simpleTestClient(tester.getBootstrapAddress(), true)) {

            // When
            var response = client.get(apiVersionsRequest());

            // Then
            assertThat(response).failsWithin(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forbiddenMode_tlsChannel_withoutProxyHeader_succeeds() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> tlsConfig(bootstrap, ProxyProtocolMode.FORBIDDEN));
                var client = tester.simpleTestClient(tester.getBootstrapAddress(), true)) {
            tester.addMockResponseForApiKey(new ResponsePayload(ApiKeys.API_VERSIONS,
                    ApiVersionsRequestData.HIGHEST_SUPPORTED_VERSION, new ApiVersionsResponseData()));

            // When
            var response = client.get(apiVersionsRequest());

            // Then
            assertThat(response).succeedsWithin(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forbiddenMode_tlsChannel_withProxyHeader_rejects() {
        // Given
        try (var tester = mockKafkaKroxyliciousTester(bootstrap -> tlsConfig(bootstrap, ProxyProtocolMode.FORBIDDEN))) {
            var parts = tester.getBootstrapAddress().split(":", 2);
            try (var client = KafkaClient.withProxyProtocol(parts[0], Integer.parseInt(parts[1]),
                    proxyMessage(), TRUST_ALL_CLIENT_SSL_CONTEXT)) {

                // When
                var response = client.get(apiVersionsRequest());

                // Then
                assertThat(response).failsWithin(5, TimeUnit.SECONDS);
            }
        }
    }

    // ---- helpers ----

    private static Request apiVersionsRequest() {
        return new Request(ApiKeys.API_VERSIONS, ApiVersionsRequestData.HIGHEST_SUPPORTED_VERSION,
                "test-client", new ApiVersionsRequestData());
    }

    private static ConfigurationBuilder plainConfig(String bootstrap, ProxyProtocolMode mode) {
        // @formatter:off
        return baseConfigurationBuilder()
                .addNewClusterDefinition()
                    .withName(DEFAULT_CLUSTER_DEF_NAME)
                    .withBootstrapServers(bootstrap)
                .endClusterDefinition()
                .addNewVirtualCluster()
                    .withName("demo")
                    .withTarget(DEFAULT_CLUSTER_TARGET)
                    .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(OS_ASSIGNED_BOOTSTRAP)
                            .withNewProxyProtocol()
                                .withMode(mode)
                            .endProxyProtocol()
                            .build())
                .endVirtualCluster();
        // @formatter:on
    }

    private static ConfigurationBuilder tlsConfig(String bootstrap, ProxyProtocolMode mode) {
        var keystore = TLS_KEYS.jksServerKeystore();
        // @formatter:off
        return baseConfigurationBuilder()
                .addNewClusterDefinition()
                    .withName(DEFAULT_CLUSTER_DEF_NAME)
                    .withBootstrapServers(bootstrap)
                .endClusterDefinition()
                .addNewVirtualCluster()
                    .withName("demo")
                    .withTarget(DEFAULT_CLUSTER_TARGET)
                    .addToGateways(defaultPortIdentifiesNodeGatewayBuilder(OS_ASSIGNED_BOOTSTRAP)
                            .withNewProxyProtocol()
                                .withMode(mode)
                            .endProxyProtocol()
                            .withNewTls()
                                .withNewKeyStoreKey()
                                    .withStoreFile(keystore.path().toString())
                                    .withStoreType(keystore.type())
                                    .withNewInlinePasswordStoreProvider(keystore.storePassword())
                                    .withNewInlinePasswordKeyProvider(keystore.keyPassword())
                                .endKeyStoreKey()
                            .endTls()
                            .build())
                .endVirtualCluster();
        // @formatter:on
    }
}
