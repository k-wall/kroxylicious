/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.kroxylicious.proxy.config.ClusterNetworkAddressConfigProviderDefinition;
import io.kroxylicious.proxy.config.ClusterNetworkAddressConfigProviderDefinitionBuilder;
import io.kroxylicious.proxy.config.ConfigurationBuilder;
import io.kroxylicious.proxy.config.FilterDefinitionBuilder;
import io.kroxylicious.proxy.config.VirtualClusterBuilder;
import io.kroxylicious.proxy.service.HostPort;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.common.KeytoolCertificateGenerator;
import io.kroxylicious.testing.kafka.common.Tls;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.test.tester.KroxyliciousTesters.kroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests focused on Kroxylicious ability to use TLS for both the upstream and downstream.
 *
 * TODO add integration tests covering kroylicious's ability to use JKS and PEM material. Needs https://github.com/kroxylicious/kroxylicious-junit5-extension/issues/120
 */
@ExtendWith(KafkaClusterExtension.class)
public class TlsIT {
    private static final HostPort PROXY_ADDRESS = HostPort.parse("localhost:9192");
    private static final ClusterNetworkAddressConfigProviderDefinition CONFIG_PROVIDER_DEFINITION = new ClusterNetworkAddressConfigProviderDefinitionBuilder(
            "PortPerBroker").withConfig("bootstrapAddress", PROXY_ADDRESS)
            .build();
    private static final String TOPIC = "my-test-topic";
    @TempDir
    private Path certsDirectory;
    private KeytoolCertificateGenerator downstreamCertificateGenerator;
    private Path clientTrustStore;

    @BeforeEach
    public void beforeEach() throws Exception {
        // Note that the KeytoolCertificateGenerator generates key stores that are PKCS12 format.
        this.downstreamCertificateGenerator = new KeytoolCertificateGenerator();
        this.downstreamCertificateGenerator.generateSelfSignedCertificateEntry("test@redhat.com", "localhost", "KI", "RedHat", null, null, "US");
        this.clientTrustStore = certsDirectory.resolve("kafka.truststore.jks");
        this.downstreamCertificateGenerator.generateTrustStore(this.downstreamCertificateGenerator.getCertFilePath(), "client",
                clientTrustStore.toAbsolutePath().toString());
    }

    @Test
    public void upstreamUsesSelfSignedTls_TrustStore(@Tls KafkaCluster cluster) {
        var bootstrapServers = cluster.getBootstrapServers();
        var brokerTruststore = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);
        assertThat(brokerTruststore).isNotEmpty();
        assertThat(brokerTruststorePassword).isNotEmpty();

        var builder = new ConfigurationBuilder()
                .addToVirtualClusters("demo", new VirtualClusterBuilder()
                        .withNewTargetCluster()
                        .withBootstrapServers(bootstrapServers)
                        .withNewTls()
                        .withNewTrustStoreTrust()
                        .withStoreFile(brokerTruststore)
                        .withNewInlinePasswordStoreProvider(brokerTruststorePassword)
                        .endTrustStoreTrust()
                        .endTls()
                        .endTargetCluster()
                        .withClusterNetworkAddressConfigProvider(CONFIG_PROVIDER_DEFINITION)
                        .build())
                .addToFilters(new FilterDefinitionBuilder("ApiVersions").build());

        try (var tester = kroxyliciousTester(builder); var admin = tester.admin("demo")) {
            // do some work to ensure connection is opened
            createTopic(admin, TOPIC, 1);
        }
    }

    @Test
    public void upstreamUsesSelfSignedTls_TrustX509(@Tls KafkaCluster cluster) throws Exception {
        var bootstrapServers = cluster.getBootstrapServers();
        var brokerTruststore = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);
        assertThat(brokerTruststore).isNotEmpty();
        assertThat(brokerTruststorePassword).isNotEmpty();

        var trustStore = KeyStore.getInstance(new File(brokerTruststore), brokerTruststorePassword.toCharArray());
        var params = new PKIXParameters(trustStore);

        var trustAnchors = params.getTrustAnchors();
        var certificates = trustAnchors.stream().map(TrustAnchor::getTrustedCert).toList();
        assertThat(certificates).isNotNull();
        assertThat(certificates).hasSizeGreaterThan(0);

        var file = writeTrustToTemporaryFile(certificates);

        var builder = new ConfigurationBuilder()
                .addToVirtualClusters("demo", new VirtualClusterBuilder()
                        .withNewTargetCluster()
                        .withBootstrapServers(bootstrapServers)
                        .withNewTls()
                        .withNewTrustStoreTrust()
                        .withStoreFile(file.getAbsolutePath())
                        .withStoreType("PEM")
                        .endTrustStoreTrust()
                        .endTls()
                        .endTargetCluster()
                        .withClusterNetworkAddressConfigProvider(CONFIG_PROVIDER_DEFINITION)
                        .build())
                .addToFilters(new FilterDefinitionBuilder("ApiVersions").build());

        try (var tester = kroxyliciousTester(builder); var admin = tester.admin("demo")) {
            // do some work to ensure connection is opened
            createTopic(admin, TOPIC, 1);
        }
    }

    @Test
    public void upstreamUsesTlsInsecure(@Tls KafkaCluster cluster) throws Exception {
        var bootstrapServers = cluster.getBootstrapServers();

        var builder = new ConfigurationBuilder()
                .addToVirtualClusters("demo", new VirtualClusterBuilder()
                        .withNewTargetCluster()
                        .withBootstrapServers(bootstrapServers)
                        .withNewTls()
                        .withNewInsecureTlsTrust(true)
                        .endTls()
                        .endTargetCluster()
                        .withClusterNetworkAddressConfigProvider(CONFIG_PROVIDER_DEFINITION)
                        .build())
                .addToFilters(new FilterDefinitionBuilder("ApiVersions").build());

        try (var tester = kroxyliciousTester(builder); var admin = tester.admin("demo")) {
            // do some work to ensure connection is opened
            createTopic(admin, TOPIC, 1);
        }
    }

    @Test
    public void downstreamAndUpstreamTls(@Tls KafkaCluster cluster) {
        var bootstrapServers = cluster.getBootstrapServers();
        var brokerTruststore = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        var brokerTruststorePassword = (String) cluster.getKafkaClientConfiguration().get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);
        assertThat(brokerTruststore).isNotEmpty();
        assertThat(brokerTruststorePassword).isNotEmpty();

        var builder = new ConfigurationBuilder()
                .addToVirtualClusters("demo", new VirtualClusterBuilder()
                        .withNewTargetCluster()
                        .withBootstrapServers(bootstrapServers)
                        .withNewTls()
                        .withNewTrustStoreTrust()
                        .withStoreFile(brokerTruststore)
                        .withNewInlinePasswordStoreProvider(brokerTruststorePassword)
                        .endTrustStoreTrust()
                        .endTls()
                        .endTargetCluster()
                        .withNewTls()
                        .withNewKeyStoreKey()
                        .withStoreFile(downstreamCertificateGenerator.getKeyStoreLocation())
                        .withNewInlinePasswordStoreProvider(downstreamCertificateGenerator.getPassword())
                        .endKeyStoreKey()
                        .endTls()
                        .withClusterNetworkAddressConfigProvider(CONFIG_PROVIDER_DEFINITION)
                        .build())
                .addToFilters(new FilterDefinitionBuilder("ApiVersions").build());

        try (var tester = kroxyliciousTester(builder);
                var admin = tester.admin("demo",
                        Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name,
                                SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, clientTrustStore.toAbsolutePath().toString(),
                                SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, downstreamCertificateGenerator.getPassword()))) {
            // do some work to ensure connection is opened
            createTopic(admin, TOPIC, 1);
        }
    }

    private void createTopic(Admin admin, String topic, int numPartitions) {
        try {
            admin.createTopics(List.of(new NewTopic(topic, numPartitions, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
        catch (InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private File writeTrustToTemporaryFile(List<X509Certificate> certificates) throws IOException {
        var file = File.createTempFile("trust", ".pem");
        var mimeLineEnding = new byte[]{ '\r', '\n' };

        try (var out = new FileOutputStream(file)) {
            certificates.forEach(c -> {
                var encoder = Base64.getMimeEncoder();
                try {
                    out.write("-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.UTF_8));
                    out.write(mimeLineEnding);
                    out.write(encoder.encode(c.getEncoded()));
                    out.write(mimeLineEnding);
                    out.write("-----END CERTIFICATE-----".getBytes(StandardCharsets.UTF_8));
                    out.write(mimeLineEnding);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                catch (CertificateEncodingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return file;
    }
}
