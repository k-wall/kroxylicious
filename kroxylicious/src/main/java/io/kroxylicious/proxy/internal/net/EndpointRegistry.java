/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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
public class EndpointRegistry implements AutoCloseable, EndpointResolver {

    interface RoutingKey {
        RoutingKey NULL_ROUTING_KEY = new NullRoutingKey();

        static RoutingKey createBindingKey(String sniHostname) {
            if (sniHostname == null || sniHostname.isEmpty()) {
                return NULL_ROUTING_KEY;
            }
            return new SniRoutingKey(sniHostname);
        }

    }

    private static class NullRoutingKey implements RoutingKey {
        @Override
        public String toString() {
            return "NullRoutingKey[]";
        }
    }

    private record SniRoutingKey(String sniHostname) implements RoutingKey {

    private SniRoutingKey(String sniHostname) {
            Objects.requireNonNull(sniHostname);
            this.sniHostname = sniHostname.toLowerCase(Locale.ROOT);
        }}

    protected static final AttributeKey<Map<RoutingKey, VirtualClusterBinding>> CHANNEL_BINDINGS = AttributeKey.newInstance("channelBindings");

    private record VirtualClusterRecord(CompletionStage<Endpoint> registration, AtomicReference<CompletionStage<Void>> deregistration) {

    private static VirtualClusterRecord createVirtualClusterRecord() {
            return new VirtualClusterRecord(new CompletableFuture<>(), new AtomicReference<>());
        }}

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

        var vcr = VirtualClusterRecord.createVirtualClusterRecord();
        var current = registeredVirtualClusters.putIfAbsent(virtualCluster, vcr);
        if (current != null) {
            return current.registration();
        }

        var provider = virtualCluster.getClusterEndpointProvider();
        var tls = virtualCluster.isUseTls();
        var bootstrapAddress = provider.getClusterBootstrapAddress();
        var bindingAddress = provider.getBindAddress().orElse(null);

        var initialBindings = new LinkedHashMap<HostPort, Integer>();
        initialBindings.put(bootstrapAddress, null); // bootstrap is at index 0.
        for (int i = 0; i < provider.getNumberOfBrokerEndpointsToPrebind(); i++) {
            initialBindings.put(provider.getBrokerAddress(i), i);
        }

        var endpointFutures = initialBindings.entrySet().stream().map(e -> {
            var hp = e.getKey();
            var nodeId = e.getValue();
            var key = Endpoint.createEndpoint(bindingAddress, hp.port(), tls);
            return registerBinding(key, hp.host(), new VirtualClusterBinding(virtualCluster, nodeId)).toCompletableFuture();
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
                registeredVirtualClusters.remove(virtualCluster);
                future.completeExceptionally(t);
            }
        });

        return vcr.registration();
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

        return vcr.registration()
                .thenCompose((u) -> unregisterBinding(virtualCluster, binding -> binding.virtualCluster().equals(virtualCluster)).thenApply((unused1) -> {
                    registeredVirtualClusters.remove(virtualCluster);
                    return null;
                }));
    }

    private CompletionStage<Endpoint> registerBinding(Endpoint key, String host, VirtualClusterBinding virtualClusterBinding) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(virtualClusterBinding, "virtualClusterBinding cannot be null");
        var virtualCluster = virtualClusterBinding.virtualCluster();

        var channelStage = listeningChannels.computeIfAbsent(key, (u) -> {
            var bindReq = NetworkBindRequest.createNetworkBindRequest(key, virtualCluster.isUseTls());
            queue.add(bindReq);
            return bindReq.getCompletionStage();
        });

        return channelStage.thenApply(c -> {
            var bindings = c.attr(CHANNEL_BINDINGS);
            var bindingKey = virtualCluster.getClusterEndpointProvider().requiresTls() ? RoutingKey.createBindingKey(host) : RoutingKey.NULL_ROUTING_KEY;
            bindings.setIfAbsent(new ConcurrentHashMap<>());

            Map<RoutingKey, VirtualClusterBinding> bindingMap = bindings.get();
            var existing = bindingMap.putIfAbsent(bindingKey, virtualClusterBinding);
            if (existing != null) {
                throw new EndpointBindingException("Endpoint %s cannot be bound with key %s, that key is already bound".formatted(key, bindingKey));
            }
            return key;
        });
    }

    private CompletionStage<Void> unregisterBinding(VirtualCluster virtualCluster, Predicate<VirtualClusterBinding> predicate) {
        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");
        Objects.requireNonNull(predicate, "predicate cannot be null");

        // TODO cache sufficient information on the virtualclusterrecord to avoid the o(n)
        var unbindFutures = listeningChannels.entrySet().stream().map((e) -> e.getValue().thenApply((channel) -> {
            var bindingMap = channel.attr(EndpointRegistry.CHANNEL_BINDINGS).get();
            var allEntries = bindingMap.entrySet();
            var toRemove = allEntries.stream().filter(be -> predicate.test(be.getValue())).collect(Collectors.toSet());
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

        return CompletableFuture.allOf(unbindFutures.toArray(new CompletableFuture<?>[0]));
    }

    @Override
    public CompletionStage<VirtualClusterBinding> resolve(String bindingAddress, int port, String sniHostname, boolean tls) {
        var endpoint = new Endpoint(bindingAddress, port, tls);
        CompletionStage<Channel> channelCompletionStage = this.listeningChannels.get(endpoint);
        if (channelCompletionStage == null) {
            return CompletableFuture.failedStage(buildEndpointResolutionException("Failed to find channel matching ", bindingAddress, port, sniHostname, tls));
        }
        return channelCompletionStage.thenApply(channel -> {
            var bindings = channel.attr(CHANNEL_BINDINGS);
            if (bindings == null || bindings.get() == null) {
                throw buildEndpointResolutionException("No channel bindings found for ", bindingAddress, port, sniHostname, tls);
            }
            // We first look for a binding matching by SNI name, then fallback to a null match.
            var binding = bindings.get().getOrDefault(RoutingKey.createBindingKey(sniHostname), bindings.get().get(RoutingKey.NULL_ROUTING_KEY));
            if (binding == null) {
                throw buildEndpointResolutionException("No channel bindings found for ", bindingAddress, port, sniHostname, tls);
            }
            return binding;
        });
    }

    private EndpointResolutionException buildEndpointResolutionException(String prefix, String bindingAddress, int port, String sniHostname, boolean tls) {
        return new EndpointResolutionException(
                ("%s binding address: %s, port %d, sniHostname: %s, tls %s").formatted(prefix,
                        bindingAddress == null ? "any" : bindingAddress,
                        port,
                        sniHostname == null ? "<none>" : sniHostname,
                        tls));
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
