/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.service.HostPort;

/**
 * PortIdentifiesNode.
 *
 * @param bootstrapAddress a {@link HostPort} defining the host and port of the bootstrap address. Required.
 * @param nodeAddressPattern an address pattern used to form broker addresses.  It is addresses made from this pattern that are returned to the kafka
 *        client in the Metadata response so must be resolvable by the client.  One pattern is supported: {@code $(nodeId)} which interpolates the node
 *        id into the address. If nodeAddressPattern is omitted, it defaulted it based on the host name of {@code bootstrapAddress}. Optional.
 * @param nodeStartPort defines the starting range of port number that will be assigned to the brokers.  If omitted, it is defaulted to the port number
 *        of {@code bootstrapAddress + 1}. Optional.
 * @param nodeIdRanges defines the node id ranges present in the target cluster. Cannot be used if lowestTargetNodeId or numberOfBrokerPorts is specified. Optional.
 * @param lowestTargetNodeId defines the lowest node id used by the target broker. If omitted, it is defaulted to 0. Cannot be used if nodeIdRanges is specified. Optional.
 * @param numberOfBrokerPorts defines the maximum number of broker ports that will be permitted. If omitted, it is defaulted to {$code 3}. Cannot be used if nodeIdRanges is specified. Optional.
 */
public record PortIdentifiesNode(@JsonProperty(required = true) HostPort bootstrapAddress,
                                 @JsonProperty(required = false) String nodeAddressPattern,
                                 @JsonProperty(required = false) Integer nodeStartPort,
                                 @JsonProperty(required = false) List<NamedRange> nodeIdRanges,
                                 @JsonProperty(required = false, defaultValue = "0") Integer lowestTargetNodeId,
                                 @JsonProperty(required = false, defaultValue = "3") Integer numberOfBrokerPorts) {

    public PortIdentifiesNode {
        Objects.requireNonNull(bootstrapAddress);
        if (nodeIdRanges != null) {
            if (lowestTargetNodeId != null) {
                throw new IllegalConfigurationException("Configuration property 'lowestTargetNodeId' cannot be used if 'nodeIdRanges' is specified.");
            }
            if (numberOfBrokerPorts != null) {
                throw new IllegalConfigurationException("Configuration property 'numberOfBrokerPorts' cannot be used if 'nodeIdRanges' is specified.");
            }
        }
    }
}
