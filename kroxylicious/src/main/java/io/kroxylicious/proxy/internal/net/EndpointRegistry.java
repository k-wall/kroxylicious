/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import io.kroxylicious.proxy.config.VirtualCluster;
import io.kroxylicious.proxy.service.HostPort;

/**
 *

 *  *
 *
 *
 */
public class EndpointRegistry implements AutoCloseable {

    protected static final AttributeKey<Map<RoutingKey, VirtualClusterBinding>> CHANNEL_BINDINGS = AttributeKey.newInstance("channelBindings");

    private record VirtualClusterRecord(CompletionStage<Endpoint> registration,
                                        AtomicReference<CompletionStage<Void>> deregistration) {
        private static VirtualClusterRecord createVirtualClusterRecord() {
            return new VirtualClusterRecord(new CompletableFuture<>(), new AtomicReference<>());
        }
    }

    private final BlockingQueue<NetworkBindingOperation> queue = new LinkedBlockingQueue<>();

    private final Map<VirtualCluster, VirtualClusterRecord> registeredVirtualClusters = new ConcurrentHashMap<>();

    private final Map<Endpoint, CompletionStage<Channel>> listeningChannels = new ConcurrentHashMap<>();

    public boolean hasNetworkEvents() {
        return !queue.isEmpty();
    }

    public NetworkBindingOperation takeNetworkBindingEvent() throws InterruptedException {
        return queue.take();
    }

    /* test */ int countNetworkEvents() {
        return queue.size();
    }

    public CompletionStage<Endpoint> registerVirtualCluster(VirtualCluster virtualCluster) {
        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");

        var current = registeredVirtualClusters.computeIfAbsent(virtualCluster, (u) -> {
            var vcr = VirtualClusterRecord.createVirtualClusterRecord();

            var provider = virtualCluster.getClusterEndpointProvider();
            var tls = virtualCluster.isUseTls();
            var bootstrapAddress = provider.getClusterBootstrapAddress();
            var bindingAddress = provider.getBindAddress().orElse(null);

            var initialBindings = new LinkedHashMap<HostPort, Integer>();
            initialBindings.put(bootstrapAddress, null); // bootstrap is index 0.
            for (int i = 0; i < provider.getNumberOfBrokerEndpointsToPrebind(); i++) {
                initialBindings.put(provider.getBrokerAddress(i), i);
            }

            var endpointFutures = initialBindings.entrySet().stream().map(e -> {
                var hp = e.getKey();
                var nodeId = e.getValue();
                var key = Endpoint.createEndpoint(bindingAddress, hp.port(), tls);
                return registerEndpoint(key, hp.host(), virtualCluster, nodeId).toCompletableFuture();
                // TODO cache endpoints when future completes
            }).toList();

            CompletableFuture.allOf(endpointFutures.toArray(new CompletableFuture<?>[0])).whenComplete((unused, t) -> {
                var future = vcr.registration.toCompletableFuture();
                if (t == null) {
                    try {
                        future.complete(endpointFutures.get(0).get());
                    }
                    catch (InterruptedException | ExecutionException e) {
                        future.completeExceptionally(e);
                    }
                }
                else {
                    future.completeExceptionally(t);
                }
            });
            return vcr;
        });

        return current.registration();
    }

    private CompletionStage<Endpoint> registerEndpoint(Endpoint key, String host, VirtualCluster virtualCluster, Integer nodeId) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");

        var channelStage = listeningChannels.computeIfAbsent(key, (u) -> {
            var bindReq = NetworkBindRequest.createNetworkBindRequest(key, virtualCluster.isUseTls());
            queue.add(bindReq);
            return bindReq.getCompletionStage();
        });

        return channelStage.thenApply(c -> {
            var bindings = c.attr(CHANNEL_BINDINGS);
            RoutingKey bindingKey = virtualCluster.getClusterEndpointProvider().requiresTls() ? RoutingKey.createBindingKey(host) : RoutingKey.NULL_BINDING_KEY;
            bindings.setIfAbsent(new ConcurrentHashMap<>());
            bindings.get().put(bindingKey, new VirtualClusterBinding(virtualCluster, nodeId));
            return key;
        });
    }

    public CompletionStage<Void> deregisterVirtualCluster(VirtualCluster virtualCluster) {

        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");

        CompletableFuture<Void> deregisterFuture = new CompletableFuture<>();
        var vcr = registeredVirtualClusters.get(virtualCluster);
        if (vcr == null) {
            deregisterFuture.complete(null);
            return deregisterFuture;
        }

        var updated = vcr.deregistration().compareAndSet(null, deregisterFuture);
        if (!updated) {
            return vcr.deregistration().get();
        }

        return vcr.registration().thenCompose((u) -> {

            // TODO cache sufficient information on the virtualclusterrecord to avoid the o(n)
            var unbindFutures = listeningChannels.entrySet().stream().map((e) -> e.getValue().thenApply((channel) -> {
                var bindingMap = channel.attr(EndpointRegistry.CHANNEL_BINDINGS).get();
                var allEntries = bindingMap.entrySet();
                var toRemove = allEntries.stream().filter(be -> be.getValue().virtualCluster().equals(virtualCluster)).collect(Collectors.toSet());
                allEntries.removeAll(toRemove);
                if (bindingMap.isEmpty()) {
                    var unbind = NetworkUnbindRequest.createNetworkUnbindRequest(e.getKey(), virtualCluster.isUseTls(), channel);
                    queue.add(unbind);
                    return unbind.getCompletionStage();
                }
                else {
                    return CompletableFuture.completedStage(channel);
                }
            })).map(CompletionStage::toCompletableFuture).toList();

            return CompletableFuture.allOf(unbindFutures.toArray(new CompletableFuture<?>[0])).thenApply((unused1) -> {
                registeredVirtualClusters.remove(virtualCluster);
                return null;
            });
        });
    }

    public CompletionStage<VirtualClusterBinding> lookup(String bindingAddress, int port, boolean tls, String sniHostname) {
        var endpoint = new Endpoint(bindingAddress, port, tls);
        CompletionStage<Channel> channelCompletionStage = this.listeningChannels.get(endpoint);
        if (channelCompletionStage == null) {
            return CompletableFuture.completedStage(null);
        }
        return channelCompletionStage.thenApply(channel -> {
            var bindings = channel.attr(CHANNEL_BINDINGS);
            var bindingKey = RoutingKey.createBindingKey(sniHostname);
            if (bindings == null || bindings.get() == null) {
                return null;
            }
            var binding = bindings.get().get(bindingKey);
            return binding;
        });
    }

    protected record RoutingKey(String sniHostname) {
        private static final RoutingKey NULL_BINDING_KEY = new RoutingKey(null);

        public static RoutingKey createBindingKey(String sniHostname) {
            if (sniHostname == null) {
                return NULL_BINDING_KEY;
            }
            return new RoutingKey(sniHostname);
        }
    }

    public CompletionStage<Void> shutdown() {
        return CompletableFuture.allOf(
                registeredVirtualClusters.keySet().stream().map(this::deregisterVirtualCluster)
                        .map(CompletionStage::toCompletableFuture).toArray(CompletableFuture<?>[]::new));
    }

    @Override
    public void close() throws Exception {
        shutdown().toCompletableFuture().get();
    }
}
