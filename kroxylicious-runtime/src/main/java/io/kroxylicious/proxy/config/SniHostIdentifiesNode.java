/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.service.HostPort;

/**
 * SniHostIdentifiesNode.
 *
 * @param bootstrapAddress a {@link HostPort} defining the host and port of the bootstrap address (required).
 * @param advertisedBrokerAddressPattern a pattern used to derive broker addresses. It is addresses derived from this pattern that are sent to the Kafka client (so they
 *        must be resolvable and routable from the client's network).  A port number can be included.  If the port number is not included, the port number assigned
 *        to the bootstrapAddress is used.  One pattern is supported: {@code $(nodeId)} which interpolates the node id into the address (required).
 */
public record SniHostIdentifiesNode(@JsonProperty(required = true) HostPort bootstrapAddress,
                                    @JsonProperty(required = true) String advertisedBrokerAddressPattern) {
    public SniHostIdentifiesNode {
        Objects.requireNonNull(bootstrapAddress);
        Objects.requireNonNull(advertisedBrokerAddressPattern);
    }
}
