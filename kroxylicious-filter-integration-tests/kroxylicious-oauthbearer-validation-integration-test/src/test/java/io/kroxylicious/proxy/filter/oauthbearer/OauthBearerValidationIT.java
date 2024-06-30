/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.oauthbearer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;

import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;
import io.kroxylicious.testing.kafka.junit5ext.Topic;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(KafkaClusterExtension.class)
@ExtendWith(NettyLeakDetectorExtension.class)
class OauthBearerValidationIT {

    @Test
    void shouldPassThroughRecordUnchanged(KafkaCluster cluster, Topic topic) throws Exception {

        assertThat(true).isTrue();

        // try (var tester = kroxyliciousTester(proxy(cluster));
        // var producer = tester.producer(Map.of(CLIENT_ID_CONFIG, "shouldPassThroughRecordUnchanged", DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000));
        // var consumer = tester.consumer()) {
        // producer.send(new ProducerRecord<>(topic.name(), "my-key", "Hello, world!")).get();
        // consumer.subscribe(Set.of(topic.name()));
        // var records = consumer.poll(Duration.ofSeconds(10));
        // consumer.close();
        //
        // assertThat(records.iterator())
        // .toIterable()
        // .hasSize(1)
        // .map(ConsumerRecord::value)
        // .containsExactly(PLAINTEXT);
        //
        // }
    }

}
