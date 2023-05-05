/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(EndpointRegistry.class);
    private final AtomicBoolean registryClosed = new AtomicBoolean(false);

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

    private final BlockingQueue<NetworkBindingOperation<?>> queue = new LinkedBlockingQueue<>();

    private final Map<VirtualCluster, VirtualClusterRecord> registeredVirtualClusters = new ConcurrentHashMap<>();

    private final Map<Endpoint, CompletionStage<Channel>> listeningChannels = new ConcurrentHashMap<>();

    public NetworkBindingOperation<?> takeNetworkBindingEvent() throws InterruptedException {
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

        var unused = allOfFutures(endpointFutures).whenComplete((u, t) -> {
            var future = vcr.registration.toCompletableFuture();
            if (t != null) {
                // Try to roll back any bindings that were successfully made
                unregisterBinding(virtualCluster, (vcb) -> vcb.virtualCluster().equals(virtualCluster))
                        .handle((u1, t1) -> {
                            if (t1 != null) {
                                LOGGER.warn("Registration error", t);
                                LOGGER.warn("Secondary error occurred whilst handling a previous registration error: {}", t.getMessage(), t1);
                            }
                            registeredVirtualClusters.remove(virtualCluster);
                            future.completeExceptionally(t);
                            return null;
                        });
            }
            else {
                try {
                    future.complete(endpointFutures.get(0).get());
                }
                catch (Throwable t1) {
                    future.completeExceptionally(t1);
                }
            }
        });

        return vcr.registration();
    }

    public CompletionStage<Void> deregisterVirtualCluster(VirtualCluster virtualCluster) {
        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");

        var deregisterFuture = new CompletableFuture<Void>();
        var vcr = registeredVirtualClusters.get(virtualCluster);
        if (vcr == null) {
            deregisterFuture.complete(null);
            return deregisterFuture;
        }

        var updated = vcr.deregistration().compareAndSet(null, deregisterFuture);
        if (!updated) {
            return vcr.deregistration().get();
        }

        var unused = vcr.registration()
                .thenCompose((u) -> unregisterBinding(virtualCluster, binding -> binding.virtualCluster().equals(virtualCluster))
                        .handle((unused1, t) -> {
                            registeredVirtualClusters.remove(virtualCluster);
                            if (t != null) {
                                deregisterFuture.completeExceptionally(t);
                            }
                            else {
                                deregisterFuture.complete(null);
                            }
                            return null;
                        }));
        return deregisterFuture;
    }

    /* test */ boolean isRegistered(VirtualCluster virtualCluster) {
        return registeredVirtualClusters.containsKey(virtualCluster);
    }

    /* test */ int listeningChannelCount() {
        return listeningChannels.size();
    }

    private CompletionStage<Endpoint> registerBinding(Endpoint key, String host, VirtualClusterBinding virtualClusterBinding) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(virtualClusterBinding, "virtualClusterBinding cannot be null");
        var virtualCluster = virtualClusterBinding.virtualCluster();

        var future = new CompletableFuture<Channel>();
        var channelStage = listeningChannels.putIfAbsent(key, future);
        if (channelStage == null) {
            channelStage = future.exceptionally(t -> {
                // Handles the case where the network bind fails
                listeningChannels.remove(key);
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                else {
                    throw new RuntimeException(t);
                }
            });
            boolean useTls = virtualCluster.isUseTls();
            var bindReq = new NetworkBindRequest(key.bindingAddress(), key.port(), useTls, future);
            queue.add(bindReq);
        }

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
        var unbindStages = listeningChannels.entrySet().stream()
                .map((e) -> e.getValue().thenCompose(channel -> {
                    var bindingMap = channel.attr(EndpointRegistry.CHANNEL_BINDINGS).get();
                    var allEntries = bindingMap.entrySet();
                    var toRemove = allEntries.stream().filter(be -> predicate.test(be.getValue())).collect(Collectors.toSet());
                    if (allEntries.removeAll(toRemove) && bindingMap.isEmpty()) {
                        var future = new CompletableFuture<Void>();
                        future.whenComplete((u, t) -> {
                            listeningChannels.remove(e.getKey());
                        });

                        boolean useTls = virtualCluster.isUseTls();
                        var unbind = new NetworkUnbindRequest(useTls, channel, future);
                        queue.add(unbind);
                        return future;
                    }
                    else {
                        return CompletableFuture.completedFuture(null);
                    }
                })).toList();

        return allOfStage(unbindStages);
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

    public boolean isRegistryClosed() {
        return registryClosed.get();
    }

    public CompletionStage<Void> shutdown() {
        return allOfStage(registeredVirtualClusters.keySet().stream().map(this::deregisterVirtualCluster).toList())
                .whenComplete((u, t) -> {
                    LOGGER.debug("EndpointRegistry shutdown complete.");
                    registryClosed.set(true);
                });
    }

    @Override
    public void close() throws Exception {
        shutdown().toCompletableFuture().get();
    }

    private static <T> CompletableFuture<Void> allOfStage(Collection<CompletionStage<T>> futures) {
        return allOfFutures(futures.stream().map(CompletionStage::toCompletableFuture).toList());
    }

    private static <T> CompletableFuture<Void> allOfFutures(Collection<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }
}
