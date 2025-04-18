/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.netty.handler.ssl.SslContext;

import io.kroxylicious.proxy.config.TargetCluster;
import io.kroxylicious.proxy.model.VirtualClusterModel;
import io.kroxylicious.proxy.service.HostPort;

/**
 * A gateway to an endpoint.
 */
public interface EndpointGateway {
    /**
     * Target cluster associated with this listener.
     * @return target cluster
     */
    TargetCluster targetCluster();

    /**
     * true if this listener uses TLS.
     *
     * @return true if listener uses TLS.
     */
    boolean isUseTls();

    /**
     * Indicates if the provider requires that connections utilise the Server Name Indication (SNI)
     * extension to TLS.  If this is true, then the provider cannot support plain connections.
     *
     * @return true if this provider requires Server Name Indication (SNI).
     */
    boolean requiresServerNameIndication();

    VirtualClusterModel virtualCluster();

    /**
     * Bootstrap address.
     *
     * @return bootstrap address.
     */
    HostPort getClusterBootstrapAddress();

    /**
     * Broker address for given nodeId.
     *
     * @param nodeId node id
     * @return broker address
     * @throws IllegalArgumentException address for given broker node cannot be generated.
     */
    HostPort getBrokerAddress(int nodeId) throws IllegalArgumentException;

    Optional<SslContext> getDownstreamSslContext();

    /**
     * Advertised address of broker with the given node id, (advertised hostname and advertised port). This is
     * what is returned to clients and may differ from the node's bind port as presented by {@link #getBrokerAddress(int)}.
     * This enables Kroxylicious to sit behind yet another proxy that uses a different port from the kroxylicious bind port.
     * @param nodeId node id
     * @return the broker's advertised address
     * @throws IllegalArgumentException if this provider cannot produce a broker address for the given nodeId.
     */
    HostPort getAdvertisedBrokerAddress(int nodeId);

    /**
     * Bind address to be used for network binds.
     *
     * @return bind address
     */
    Optional<String> getBindAddress();

    Set<Integer> getExclusivePorts();

    Set<Integer> getSharedPorts();

    /**
     * Map of node ids to broker addresses.
     *
     * @return map of addresses
     */
    Map<Integer, HostPort> discoveryAddressMap();

    /**
     * Generates the node id implied by the given broker address (advertised hostname and bind port).
     * This method make sense only for implementation that embed node id information into the broker
     * address.  This information is used at startup time to allow a client that already in possession
     * of a broker address to reconnect to the cluster via Kroxylicious using only that address.
     * <br/>
     * This is an optional method. An implementation can return null.
     *
     * @param brokerAddress broker address
     * @return broker id
     */
    Integer getBrokerIdFromBrokerAddress(HostPort brokerAddress);

    /**
     * Get the gateways name
     * @return name
     */
    String name();

}
