/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.channel.Channel;
import io.netty.util.Attribute;

import io.kroxylicious.proxy.HostPortConverter;
import io.kroxylicious.proxy.config.VirtualCluster;
import io.kroxylicious.proxy.service.ClusterEndpointConfigProvider;
import io.kroxylicious.proxy.service.HostPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointRegistryTest {
    private final EndpointRegistry endpointRegistry = new EndpointRegistry();
    @Mock
    private VirtualCluster virtualCluster1;
    @Mock
    private ClusterEndpointConfigProvider endpointProvider1;
    @Mock
    private VirtualCluster virtualCluster2;
    @Mock
    private ClusterEndpointConfigProvider endpointProvider2;

    @AfterEach
    public void afterEach() throws Exception {
        endpointRegistry.close();
    }

    @Test
    public void registerVirtualCluster() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", false);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(f.isDone()).isTrue();
        assertThat(f.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, false));
    }

    @Test
    public void registerVirtualClusterTls() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", true);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(f.isDone()).isTrue();
        assertThat(f.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, true));
    }

    @Test
    public void registerSameVirtualClusterIsIdempotent() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", false);

        var f1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var f2 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(CompletableFuture.allOf(f1, f2).isDone()).isTrue();
        assertThat(f2.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, false));
    }

    @Test
    public void registerTwoClustersThatShareSameNetworkEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", true);
        configureVirtualClusterMock(virtualCluster2, endpointProvider2, "mycluster2:9192", true);

        var f1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var f2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(CompletableFuture.allOf(f1, f2).isDone()).isTrue();
        assertThat(f1.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, true));
        assertThat(f2.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, true));
    }

    @Test
    public void registerTwoClustersThatUseDifferentNetworkEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9191", true);
        configureVirtualClusterMock(virtualCluster2, endpointProvider2, "mycluster2:9192", true);

        var f1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var f2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9191),
                getNetworkBindRequest(9192));
        assertThat(CompletableFuture.allOf(f1, f2).isDone()).isTrue();
        assertThat(f1.get()).isEqualTo(Endpoint.createEndpoint(null, 9191, true));
        assertThat(f2.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, true));
    }

    @Test
    public void registerVirtualClusterWithBrokerAddress() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "localhost:9192", false);
        when(endpointProvider1.getNumberOfBrokerEndpointsToPrebind()).thenReturn(1);
        when(endpointProvider1.getBrokerAddress(0)).thenReturn(HostPort.parse("localhost:9193"));

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192), getNetworkBindRequest(9193));
        assertThat(f.isDone()).isTrue();
        assertThat(f.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, false));
    }

    @Test
    public void deregisterVirtualCluster() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", true);

        var bindFuture = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(bindFuture.isDone()).isTrue();

        var unbindFuture = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkUnbindRequest(9192));
        assertThat(unbindFuture.isDone()).isTrue();
    }

    @Test
    public void deregisterSameVirtualClusterIsIdempotent() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9192", true);

        var bindFuture = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(bindFuture.isDone()).isTrue();

        var unbindFuture = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkUnbindRequest(9192));
        assertThat(unbindFuture.isDone()).isTrue();

        var unbindFuture2 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue();
        assertThat(unbindFuture2.isDone()).isTrue();
    }

    @Test
    public void deregisterClusterThatSharesEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "mycluster1:9191", true);
        configureVirtualClusterMock(virtualCluster2, endpointProvider2, "mycluster2:9191", true);

        var f1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var f2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9191));
        assertThat(CompletableFuture.allOf(f1, f2).isDone()).isTrue();

        var unbindFuture1 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        // Port 9191 is shared by the second virtualcluster, so it can't be unbound yet
        verifyAndProcessNetworkEventQueue();
        assertThat(unbindFuture1.isDone()).isTrue();

        var unbindFuture2 = endpointRegistry.deregisterVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkUnbindRequest(9191));
        assertThat(unbindFuture2.isDone()).isTrue();
    }

    @Test
    public void deregisterVirtualClusterWithBrokerAddress() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "localhost:9192", false);
        when(endpointProvider1.getNumberOfBrokerEndpointsToPrebind()).thenReturn(1);
        when(endpointProvider1.getBrokerAddress(0)).thenReturn(HostPort.parse("localhost:9193"));

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192), getNetworkBindRequest(9193));
        assertThat(f.isDone()).isTrue();
        assertThat(f.get()).isEqualTo(Endpoint.createEndpoint(null, 9192, false));

        var unbindFuture = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkUnbindRequest(9193), getNetworkUnbindRequest(9192));
        assertThat(unbindFuture.isDone()).isTrue();

    }

    @ParameterizedTest
    @CsvSource({ "mycluster1:9192,true,true", "mycluster1:9192,true,false", "localhost:9192,false,false" })
    public void resolveBootstrap(@ConvertWith(HostPortConverter.class) HostPort address, boolean tls, boolean sni) throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, address.toString(), tls, sni);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(address.port()));
        assertThat(f.isDone()).isTrue();

        var binding = endpointRegistry.resolve(null, address.port(), tls ? address.host() : null, tls).toCompletableFuture().get();
        assertThat(binding).isNotNull();
        assertThat(binding.nodeId()).isNull();
        assertThat(binding.virtualCluster()).isEqualTo(virtualCluster1);
    }

    @Test
    public void resolveBrokerAddress() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "localhost:9192", false);
        when(endpointProvider1.getNumberOfBrokerEndpointsToPrebind()).thenReturn(1);
        when(endpointProvider1.getBrokerAddress(0)).thenReturn(HostPort.parse("localhost:9193"));

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192), getNetworkBindRequest(9193));
        assertThat(f.isDone()).isTrue();

        var binding = endpointRegistry.resolve(null, 9193, null, false).toCompletableFuture().get();
        assertThat(binding).isNotNull();
        assertThat(binding.nodeId()).isEqualTo(0);
        assertThat(binding.virtualCluster()).isEqualTo(virtualCluster1);
    }

    @Test
    public void resolveBootstrapUnknownPort() throws Exception {
        configureVirtualClusterMock(virtualCluster1, endpointProvider1, "localhost:9192", false);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(getNetworkBindRequest(9192));
        assertThat(f.isDone()).isTrue();

        var executionException = assertThrows(ExecutionException.class, () -> endpointRegistry.resolve(null, 9191, null, false).toCompletableFuture().get());
        assertThat(executionException).hasCauseInstanceOf(EndpointResolutionException.class);

    }

    private Channel createMockNettyChannel() {
        var channel = mock(Channel.class);
        var attr = mock(Attribute.class);
        var bindings = new ConcurrentHashMap<>();
        when(channel.attr(EndpointRegistry.CHANNEL_BINDINGS)).thenReturn(attr);
        when(attr.setIfAbsent(any())).thenReturn(bindings);
        when(attr.get()).thenReturn(bindings);
        return channel;
    }

    private NetworkBindRequest getNetworkBindRequest(int expectedPort) {
        Channel mock = createMockNettyChannel();
        return new NetworkBindRequest(null, expectedPort, false, CompletableFuture.completedFuture(mock));
    }

    private NetworkUnbindRequest getNetworkUnbindRequest(int port) {
        return new NetworkUnbindRequest(port, false, CompletableFuture.completedFuture(null), null);
    }

    private void configureVirtualClusterMock(VirtualCluster cluster, ClusterEndpointConfigProvider configProvider, String address, boolean tls) {
        configureVirtualClusterMock(cluster, configProvider, address, tls, tls);
    }

    private void configureVirtualClusterMock(VirtualCluster cluster, ClusterEndpointConfigProvider configProvider, String address, boolean tls, boolean sni) {
        when(cluster.getClusterEndpointProvider()).thenReturn(configProvider);
        when(cluster.isUseTls()).thenReturn(tls);
        when(configProvider.getClusterBootstrapAddress()).thenReturn(HostPort.parse(address));
        when(configProvider.requiresTls()).thenReturn(sni);
    }

    private void verifyAndProcessNetworkEventQueue(NetworkBindingOperation... expectedEvents) throws Exception {
        assertThat(endpointRegistry.countNetworkEvents()).as("unexpected number of events").isEqualTo(expectedEvents.length);
        var expectedEventIterator = Arrays.stream(expectedEvents).iterator();
        while (endpointRegistry.hasNetworkEvents()) {
            var networkBinding = endpointRegistry.takeNetworkBindingEvent();
            var expectedNetworkEvent = expectedEventIterator.next();
            assertThat(networkBinding.getClass()).as("unexpected binding operation").isEqualTo(expectedNetworkEvent.getClass());
            if (networkBinding instanceof NetworkBindRequest networkBindRequest) {
                assertThat(networkBindRequest.port()).isEqualTo(expectedNetworkEvent.port());
                networkBinding.complete(expectedNetworkEvent.getCompletionStage().toCompletableFuture().get());
            }
            else if (networkBinding instanceof NetworkUnbindRequest networkUnbindRequest) {
                assertThat(networkUnbindRequest.port()).isEqualTo(expectedNetworkEvent.port());
                networkBinding.complete(null);
            }
        }
        if (expectedEventIterator.hasNext()) {
            fail("Too few events consumed");
        }
    }
}