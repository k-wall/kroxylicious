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
 * The endpoint registry is responsible for associating network endpoints with broker/bootstrap addresses of virtual clusters.
 * Pictorially the registry looks like this:
 *
 * <pre><code>
 * Registry
 *    ├─ Endpoint (tls 9092)
 *    │    ╰───┬──→ cluster1.kafka.com         ──→ Virtual Cluster A bootstrap
 *    │        ├──→ broker1-cluster1.kafka.com ──→ Virtual Cluster A broker node 1
 *    │        ╰──→ broker2-cluster1.kafka.com ──→ Virtual Cluster A broker node 2
 *    ├─ Endpoint (plain 19092)
 *    │        ╰───→  (null)                   ──→ Virtual Cluster B bootstrap
 *    ├─ Endpoint (plain 19093)
 *    │        ╰───→  (null)                   ──→ Virtual Cluster B broker 1
 *    ╰─ Endpoint (plain 12094)
 *             ╰───→  (null)                   ──→ Virtual Cluster B broker 2
 * </code></pre>
 *
 * The association from endpoint to virtual cluster/broker is represented by a {@link RoutingKey} instance and
 * a {@link VirtualClusterBinding}.  The {@link RoutingKey} encapsulates a hostname (that is used to match against
 * the SNI name), or a null routing used for a broker per port pattern. A {@link VirtualClusterBinding}
 * identifies a bootstrap.  The {@link VirtualClusterBrokerBinding} subclass identifies a broker node binding.
 * <br/>
 * The registry does not take direct responsibility for binding and unbinding network sockets.  Instead, it exposes
 * a queue of network binding operation requests {@link NetworkBindingOperation}.  Callers get the next network
 * event by calling the {@link #takeNetworkBindingEvent()}.  Once the underlying network operation is complete, the caller
 * must complete the provided future.
 * <br/>
 * The registry exposes methods for the registration {@link #registerVirtualCluster(VirtualCluster)} and deregistration
 * {@link #deregisterVirtualCluster(VirtualCluster)} of virtual clusters.  The registry emits the required network binding
 * operations to expose the virtual cluster to the network.  These API calls return futures that will complete once
 * the underlying network operations are completed.
 * <br/>
 * The registry provides an {@link EndpointResolver}.  The {@link #resolve(String, int, String, boolean)} method accepts
 * connection metadata (port, SNI etc) and resolves this to a @{@link VirtualClusterBinding}.  This allows
 * Kroxylicious to determine the destination of any incoming connection.
 * <br/>
 * The registry is thread safe for all operations.
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

    /** Used to track the registration state of the virtual clusters in the registry */
    private record VirtualClusterRecord(CompletionStage<Endpoint> registration, AtomicReference<CompletionStage<Void>> deregistration) {

    private static VirtualClusterRecord createVirtualClusterRecord() {
            return new VirtualClusterRecord(new CompletableFuture<>(), new AtomicReference<>());
        }}

    /** Queue of network binding operations */
    private final BlockingQueue<NetworkBindingOperation<?>> queue = new LinkedBlockingQueue<>();

    /** Registry of virtual clusters that have been registered */
    private final Map<VirtualCluster, VirtualClusterRecord> registeredVirtualClusters = new ConcurrentHashMap<>();

    /** Used to track the state of the channels in the registry */
    private record ListeningChannelRecord(CompletionStage<Channel> bind, AtomicReference<CompletionStage<Void>> unbind) {

    private static ListeningChannelRecord createListeningChannelRecord() {
            return new ListeningChannelRecord(new CompletableFuture<>(), new AtomicReference<>());
        }}

    /** Registry of endpoints and their underlying Netty Channel */
    private final Map<Endpoint, ListeningChannelRecord> listeningChannels = new ConcurrentHashMap<>();

    /**
     * Blocking operation that gets the next binding operation.  Once the network operation is complete,
     * the caller must complete the {@link NetworkBindingOperation#getFuture()}.
     *
     * @return network binding operation
     *
     * @throws InterruptedException - operation interrupted
     */
    public NetworkBindingOperation<?> takeNetworkBindingEvent() throws InterruptedException {
        return queue.take();
    }

    /* test */ int countNetworkEvents() {
        return queue.size();
    }

    /**
     * Registers a virtual cluster with the registry.  The registry refers to the endpoint configuration
     * of the virtual cluster to understand its port requirements, binding new listening ports as necessary.
     * This operation returns a {@link CompletionStage<Endpoint>}.  The completion stage will complete once
     * all necessary network bind operations are completed.  The returned {@link Endpoint} refers to the
     * bootstrap endpoint of the virtual cluster.
     *
     * @param virtualCluster virtual cluster to be registered.
     * @return completion stage that will complete after registration is finished.
     */
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
            VirtualClusterBinding virtualClusterBinding = VirtualClusterBinding.createBinding(virtualCluster, nodeId);
            return registerBinding(key, hp.host(), virtualClusterBinding).toCompletableFuture();
            // TODO cache endpoints when future completes
        }).toList();

        var unused = allOfFutures(endpointFutures).whenComplete((u, t) -> {
            var future = vcr.registration.toCompletableFuture();
            if (t != null) {
                // Try to roll back any bindings that were successfully made
                deregisterBinding(virtualCluster, (vcb) -> vcb.virtualCluster().equals(virtualCluster))
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

    /**
     * Deregisters a virtual cluster from the registry, removing all existing endpoint bindings, and
     * closing any listening sockets that are no longer required. This operation returns a {@link CompletionStage<Endpoint>}.
     * The completion stage will complete once any necessary network unbind operations are completed.
     *
     * @param virtualCluster virtual cluster to be deregistered.
     * @return completion stage that will complete after registration is finished.
     */
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
                .thenCompose((u) -> deregisterBinding(virtualCluster, binding -> binding.virtualCluster().equals(virtualCluster))
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

        var future = ListeningChannelRecord.createListeningChannelRecord(); // TODO rename me
        var channelStage = listeningChannels.putIfAbsent(key, future);
        if (channelStage == null) {
            channelStage = future;
//            channelStage = future.bind().exceptionally(t -> {
//                // Handles the case where the network bind fails
//                listeningChannels.remove(key);
//                if (t instanceof RuntimeException re) {
//                    throw re;
//                }
//                else {
//                    throw new RuntimeException(t);
//                }
//            });
            boolean useTls = virtualCluster.isUseTls();
            var bindReq = new NetworkBindRequest(key.bindingAddress(), key.port(), useTls, future.bind());
            queue.add(bindReq);
        }

        return channelStage.bind().thenApply(c -> {
            var bindings = c.attr(CHANNEL_BINDINGS);
            var bindingKey = virtualCluster.getClusterEndpointProvider().requiresTls() ? RoutingKey.createBindingKey(host) : RoutingKey.NULL_ROUTING_KEY;
            bindings.setIfAbsent(new ConcurrentHashMap<>());
            var bindingMap = bindings.get();

            var existing = bindingMap.putIfAbsent(bindingKey, virtualClusterBinding);

            if (existing != null) {
                throw new EndpointBindingException("Endpoint %s cannot be bound with key %s, that key is already bound".formatted(key, bindingKey));
            }

            return key;
        });
    }

    private CompletionStage<Void> deregisterBinding(VirtualCluster virtualCluster, Predicate<VirtualClusterBinding> predicate) {
        Objects.requireNonNull(virtualCluster, "virtualCluster cannot be null");
        Objects.requireNonNull(predicate, "predicate cannot be null");

        // Search the listening channels for bindings matching the predicate. We could cache more information on the vcr to optimise
        var unbindStages = listeningChannels.entrySet().stream()
                .map((e) -> e.getValue().thenCompose(channel -> {
                    var bindingMap = channel.attr(EndpointRegistry.CHANNEL_BINDINGS).get();
                    var allEntries = bindingMap.entrySet();
                    var toRemove = allEntries.stream().filter(be -> predicate.test(be.getValue())).collect(Collectors.toSet());
                    // If our removal leaves the channel without bindings, trigger its unbinding
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

    /**
     * Uses channel metadata (port, SNI name etc.) from the incoming connection to resolve a {@link VirtualClusterBinding}.
     *
     * @param bindingAddress binding address of the accepting socket
     * @param port target port of this connection
     * @param sniHostname SNI hostname, may be null.
     * @param tls true if this connection uses TLS.
     * @return completion stage yielding the {@link VirtualClusterBinding} or exceptionally a EndpointResolutionException.
     */
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

    /**
     * Indicates if the registry is in closed state.
     * @return true if the registry has been closed.
     */
    public boolean isRegistryClosed() {
        return registryClosed.get();
    }

    /**
     * Signals that the registry should be shut down.  The operation returns a {@link java.util.concurrent.CompletionStage}
     * that will complete once shutdown is complete (endpoints unbound).
     *
     * @return CompletionStage that completes once shut-down is complete.
     */
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
