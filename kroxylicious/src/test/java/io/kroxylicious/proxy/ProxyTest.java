/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import io.debezium.kafka.KafkaCluster;
import io.kroxylicious.proxy.filter.FilterChainFactory;
import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.internal.filter.ApiVersionsFilter;
import io.kroxylicious.proxy.internal.filter.BrokerAddressFilter;
import io.kroxylicious.proxy.internal.filter.BrokerAddressFilter.AddressMapping;
import io.kroxylicious.proxy.internal.filter.FetchResponseTransformationFilter;
import io.kroxylicious.proxy.internal.filter.ProduceRequestTransformationFilter;
import io.kroxylicious.proxy.util.SystemTest;

import static java.lang.Integer.parseInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SystemTest
public class ProxyTest {

    @Test
    public void shouldPassThroughRecordUnchanged() throws Exception {
        String proxyHost = "localhost";
        int proxyPort = 9192;
        String proxyAddress = String.format("%s:%d", proxyHost, proxyPort);

        String brokerList = startKafkaCluster();

        FilterChainFactory filterChainFactory = () -> new KrpcFilter[]{
                new ApiVersionsFilter(),
                new BrokerAddressFilter(new FixedAddressMapping(proxyHost, proxyPort))
        };

        var proxy = startProxy(proxyHost, proxyPort, brokerList, filterChainFactory);

        var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        producer.send(new ProducerRecord<>("my-test-topic", "my-key", "Hello, world!")).get();
        producer.close();

        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        consumer.subscribe(Set.of("my-test-topic"));
        var records = consumer.poll(Duration.ofSeconds(10));
        consumer.close();
        assertEquals(1, records.count());
        assertEquals("Hello, world!", records.iterator().next().value());

        // shutdown the proxy
        proxy.shutdown();
    }

    @Test
    public void shouldModifyProduceMessage() throws Exception {
        String proxyHost = "localhost";
        int proxyPort = 9192;
        String proxyAddress = String.format("%s:%d", proxyHost, proxyPort);

        String brokerList = startKafkaCluster();

        FilterChainFactory filterChainFactory = () -> new KrpcFilter[]{
                new ApiVersionsFilter(),
                new BrokerAddressFilter(new FixedAddressMapping(proxyHost, proxyPort)),
                new ProduceRequestTransformationFilter(
                        buffer -> ByteBuffer.wrap(new String(StandardCharsets.UTF_8.decode(buffer).array()).toUpperCase().getBytes(StandardCharsets.UTF_8)))
        };

        var proxy = startProxy(proxyHost, proxyPort, brokerList,
                filterChainFactory);

        var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_600_000));
        producer.send(new ProducerRecord<>("my-test-topic", "my-key", "Hello, world!")).get();
        producer.close();

        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        consumer.subscribe(Set.of("my-test-topic"));
        var records = consumer.poll(Duration.ofSeconds(10));
        consumer.close();
        assertEquals(1, records.count());
        assertEquals("HELLO, WORLD!", records.iterator().next().value());

        // shutdown the proxy
        proxy.shutdown();
    }

    @Test
    public void shouldModifyFetchMessage() throws Exception {
        String proxyHost = "localhost";
        int proxyPort = 9192;
        String proxyAddress = String.format("%s:%d", proxyHost, proxyPort);

        String brokerList = startKafkaCluster();

        FilterChainFactory filterChainFactory = () -> new KrpcFilter[]{
                new ApiVersionsFilter(),
                new BrokerAddressFilter(new FixedAddressMapping(proxyHost, proxyPort)),
                new FetchResponseTransformationFilter(
                        buffer -> ByteBuffer.wrap(new String(StandardCharsets.UTF_8.decode(buffer).array()).toUpperCase().getBytes(StandardCharsets.UTF_8)))
        };

        var proxy = startProxy(proxyHost, proxyPort, brokerList,
                filterChainFactory);

        var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_600_000));
        producer.send(new ProducerRecord<>("my-test-topic", "my-key", "Hello, world!")).get();
        producer.close();

        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        consumer.subscribe(Set.of("my-test-topic"));

        var records = consumer.poll(Duration.ofSeconds(100));
        consumer.close();
        assertEquals(1, records.count());
        assertEquals("HELLO, WORLD!", records.iterator().next().value());

        // shutdown the proxy
        proxy.shutdown();
    }

    private KafkaProxy startProxy(String proxyHost,
                                  int proxyPort,
                                  String brokerList,
                                  FilterChainFactory filterChainFactory)
            throws InterruptedException {
        String[] hostPort = brokerList.split(",")[0].split(":");

        KafkaProxy kafkaProxy = new KafkaProxy(proxyHost,
                proxyPort,
                hostPort[0],
                parseInt(hostPort[1]),
                true,
                true,
                false,
                filterChainFactory);
        kafkaProxy.startup();
        return kafkaProxy;
    }

    private String startKafkaCluster() throws IOException {
        var kafkaCluster = new KafkaCluster()
                .addBrokers(1)
                .usingDirectory(Files.createTempDirectory(ProxyTest.class.getName()).toFile())
                // .withKafkaConfiguration()
                .startup();

        return kafkaCluster.brokerList();
    }

    private static class FixedAddressMapping implements AddressMapping {

        private final String targetHost;
        private final int targetPort;

        public FixedAddressMapping(String targetHost, int targetPort) {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }

        @Override
        public String downstreamHost(String host, int port) {
            return targetHost;
        }

        @Override
        public int downstreamPort(String host, int port) {
            return targetPort;
        }
    }
}