/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.informer;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.AbstractEventSource;
import io.javaoperatorsdk.operator.processing.event.source.Cache;
import io.javaoperatorsdk.operator.processing.event.source.PrimaryToSecondaryMapper;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;

/**
 * An EventSource that wraps a shared Fabric8 SharedIndexInformer, allowing
 * multiple reconcilers to share the same underlying informer cache while each
 * having their own event handling and mapping logic.
 * <p>
 * Unlike JOSDK's {@code InformerEventSource}, this class does not suppress
 * events originating from the reconciler's own writes (no
 * {@code TemporaryResourceCache} or skip logic). It is therefore only suitable
 * for secondary resources that the reconciler reads but does not create or
 * modify (e.g. Secrets and ConfigMaps referenced by CRs).
 *
 * @param <P> the primary resource type (e.g., KafkaService)
 * @param <R> the secondary resource type (e.g., Secret)
 */
public class SharedInformerEventSource<P extends HasMetadata, R extends HasMetadata>
        extends AbstractEventSource<R, P>
        implements Cache<R>, ResourceEventHandler<R> {

    private final SharedIndexInformer<R> sharedInformer;
    private final SecondaryToPrimaryMapper<R> secondaryToPrimaryMapper;
    private final PrimaryToSecondaryMapper<P> primaryToSecondaryMapper;
    private final Set<String> allowedNamespaces;

    /**
     * Creates a SharedInformerEventSource.
     *
     * @param resourceClass the secondary resource class
     * @param name the event source name
     * @param sharedInformer the shared Fabric8 informer
     * @param primaryToSecondaryMapper mapper to determine which secondary resources are related to a primary resource
     * @param secondaryToPrimaryMapper mapper to determine which primary resources are affected by secondary resource changes
     * @param allowedNamespaces namespaces to filter events (empty means all namespaces)
     */
    public SharedInformerEventSource(
                                     Class<R> resourceClass,
                                     String name,
                                     SharedIndexInformer<R> sharedInformer,
                                     PrimaryToSecondaryMapper<P> primaryToSecondaryMapper,
                                     SecondaryToPrimaryMapper<R> secondaryToPrimaryMapper,
                                     Set<String> allowedNamespaces) {
        super(resourceClass, name);
        this.sharedInformer = sharedInformer;
        this.secondaryToPrimaryMapper = secondaryToPrimaryMapper;
        this.primaryToSecondaryMapper = primaryToSecondaryMapper;
        this.allowedNamespaces = allowedNamespaces;
    }

    private boolean isAllowedNamespace(R resource) {
        return allowedNamespaces.isEmpty()
                || allowedNamespaces.contains(resource.getMetadata().getNamespace());
    }

    private boolean isAllowedNamespace(String namespace) {
        return allowedNamespaces.isEmpty() || allowedNamespaces.contains(namespace);
    }

    @Override
    public void start() {
        // Register this as an event handler on the shared informer
        sharedInformer.addEventHandler(this);
    }

    @Override
    public void stop() {
        // Remove this event handler from the shared informer
        sharedInformer.removeEventHandler(this);
        // Note: We don't stop the shared informer as other event sources may be using it
        // The SharedInformerManager is responsible for stopping shared informers
    }

    // ResourceEventHandler implementation - handles events from the shared informer

    @Override
    public void onAdd(R resource) {
        if (!isAllowedNamespace(resource)) {
            return;
        }

        Set<ResourceID> primaryResourceIDs = secondaryToPrimaryMapper.toPrimaryResourceIDs(resource);
        primaryResourceIDs.forEach(this::propagateEvent);
    }

    @Override
    public void onUpdate(R oldResource, R newResource) {
        // Namespace is immutable in Kubernetes, so checking either resource suffices.
        if (!isAllowedNamespace(newResource)) {
            return;
        }

        Set<ResourceID> oldPrimaryIDs = secondaryToPrimaryMapper.toPrimaryResourceIDs(oldResource);
        Set<ResourceID> newPrimaryIDs = secondaryToPrimaryMapper.toPrimaryResourceIDs(newResource);

        Stream.concat(oldPrimaryIDs.stream(), newPrimaryIDs.stream())
                .distinct()
                .forEach(this::propagateEvent);
    }

    @Override
    public void onDelete(R resource, boolean deletedFinalStateUnknown) {
        if (!isAllowedNamespace(resource)) {
            return;
        }

        Set<ResourceID> primaryResourceIDs = secondaryToPrimaryMapper.toPrimaryResourceIDs(resource);
        primaryResourceIDs.forEach(this::propagateEvent);
    }

    private void propagateEvent(ResourceID primaryID) {
        // The shared informer is already running when this event source registers its
        // handler, so events can arrive before JOSDK has called setEventHandler().
        // Guard against null to avoid NPE during this window.
        var handler = getEventHandler();
        if (handler != null) {
            handler.handleEvent(new io.javaoperatorsdk.operator.processing.event.Event(primaryID));
        }
    }

    // Cache implementation - delegates to the shared informer's cache

    @Override
    public Optional<R> get(ResourceID resourceID) {
        if (!isAllowedNamespace(resourceID.getNamespace().orElse(""))) {
            return Optional.empty();
        }
        String key = resourceID.getNamespace().orElse("") + "/" + resourceID.getName();
        return Optional.ofNullable(sharedInformer.getStore().getByKey(key));
    }

    @Override
    public Stream<ResourceID> keys() {
        return sharedInformer.getStore().list().stream()
                .filter(this::isAllowedNamespace)
                .map(ResourceID::fromResource);
    }

    @Override
    public Stream<R> list(Predicate<R> predicate) {
        return sharedInformer.getStore().list().stream()
                .filter(this::isAllowedNamespace)
                .filter(predicate);
    }

    @Override
    public Set<R> getSecondaryResources(P primary) {
        // Use the primary-to-secondary mapper to get ResourceIDs of related secondary resources
        Set<ResourceID> secondaryResourceIDs = primaryToSecondaryMapper.toSecondaryResourceIDs(primary);

        // Look up each ResourceID from the shared informer's cache
        return secondaryResourceIDs.stream()
                .map(this::get)
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toSet());
    }
}
