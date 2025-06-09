/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointTest {

    @Test
    void shouldCreateEndpointFromInetSocketAddress() {
        // Given
        InetSocketAddress inetSocketAddress = new InetSocketAddress("localhost", 1234);

        // When
        Endpoint actual = Endpoint.createEndpoint(inetSocketAddress, false);

        // Then
        assertThat(actual)
                .isNotNull()
                .satisfies(
                        endpoint -> {
                            assertThat(endpoint.bindingAddress()).isPresent().hasValue("127.0.0.1");
                            assertThat(endpoint.port()).isEqualTo(1234);
                        }
                );
    }

    @Test
    void shouldCreateEndpointFromPortOnly() {
        // Given
        InetSocketAddress inetSocketAddress = new InetSocketAddress(1234);

        // When
        Endpoint actual = Endpoint.createEndpoint(inetSocketAddress, false);

        // Then
        assertThat(actual)
                .isNotNull()
                .satisfies(
                        endpoint -> {
                            assertThat(endpoint.bindingAddress()).isEmpty();
                            assertThat(endpoint.port()).isEqualTo(1234);
                        }
                );
    }

    @Test
    void shouldCreateEndpointForAnyLocalAddress() {
        // Given
        InetSocketAddress inetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1234);

        // When
        Endpoint actual = Endpoint.createEndpoint(inetSocketAddress, false);

        // Then
        assertThat(actual)
                .isNotNull()
                .satisfies(
                        endpoint -> {
                            assertThat(endpoint.bindingAddress()).isPresent().hasValue("127.0.0.1");
                            assertThat(endpoint.port()).isEqualTo(1234);
                        }
                );
    }
}