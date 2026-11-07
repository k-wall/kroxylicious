/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaprotocolrouter;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.PrimaryToSecondaryMapper;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.common.FilterRef;
import io.kroxylicious.kubernetes.api.common.LocalRef;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolFilter;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolRouter;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaService;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaprotocolrouterspec.Routes;
import io.kroxylicious.kubernetes.operator.OperatorLoggingKeys;
import io.kroxylicious.kubernetes.operator.ResourcesUtil;
import io.kroxylicious.kubernetes.operator.SecureConfigInterpolator;
import io.kroxylicious.kubernetes.operator.checksum.Crc32ChecksumGenerator;
import io.kroxylicious.kubernetes.operator.eventsource.EventSourceBuilder;
import io.kroxylicious.kubernetes.operator.eventsource.Ref;
import io.kroxylicious.kubernetes.operator.informer.SharedInformerManager;

import static io.kroxylicious.kubernetes.operator.ResourcesUtil.name;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.namespace;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.toByNameMap;

/**
 * <p>Reconciles a {@link KafkaProtocolRouter} by checking whether the resources
 * referenced in its routes ({@code spec.routes[].targetRef} and {@code spec.routes[].filterRefs})
 * and the {@link Secret}s and {@link ConfigMap}s referred to in interpolated expressions
 * of {@code spec.configTemplate} actually exist, setting a {@link Condition.Type#ResolvedRefs}
 * {@link Condition} accordingly.</p>
 */
public class KafkaProtocolRouterReconciler implements Reconciler<KafkaProtocolRouter> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaProtocolRouterReconciler.class);
    static final String SECRETS = "secrets";
    static final String CONFIG_MAPS = "configmaps";
    static final String KAFKA_SERVICES = "kafkaServices";
    static final String ROUTER_TARGETS = "routerTargets";
    static final String FILTER_REFS = "filterRefs";

    private static final String KAFKA_SERVICE_KIND = HasMetadata.getKind(KafkaService.class);
    private static final String KAFKA_PROTOCOL_ROUTER_KIND = HasMetadata.getKind(KafkaProtocolRouter.class);

    private final KafkaProtocolRouterStatusFactory statusFactory;
    private final SecureConfigInterpolator secureConfigInterpolator;
    private final SharedInformerManager sharedInformerManager;

    /**
     * Constructs a reconciler for KafkaProtocolRouter resources.
     *
     * @param clock the clock used for condition timestamps
     * @param secureConfigInterpolator the interpolator for resolving secure config template expressions
     * @param sharedInformerManager the manager providing shared informers for Secret and ConfigMap resources
     */
    public KafkaProtocolRouterReconciler(Clock clock, SecureConfigInterpolator secureConfigInterpolator, SharedInformerManager sharedInformerManager) {
        this.statusFactory = new KafkaProtocolRouterStatusFactory(Objects.requireNonNull(clock));
        this.secureConfigInterpolator = Objects.requireNonNull(secureConfigInterpolator);
        this.sharedInformerManager = Objects.requireNonNull(sharedInformerManager);
    }

    @Override
    public List<EventSource<?, KafkaProtocolRouter>> prepareEventSources(EventSourceContext<KafkaProtocolRouter> context) {
        return List.of(
                EventSourceBuilder.forSecondary(context, Secret.class)
                        .withName(SECRETS)
                        .withSharedInformer(sharedInformerManager)
                        .withMappers(
                                configTemplateSecretMapper(),
                                configTemplateSecretSecondaryMapper(context))
                        .build(),
                EventSourceBuilder.forSecondary(context, ConfigMap.class)
                        .withName(CONFIG_MAPS)
                        .withSharedInformer(sharedInformerManager)
                        .withMappers(
                                configTemplateConfigMapMapper(),
                                configTemplateConfigMapSecondaryMapper(context))
                        .build(),
                EventSourceBuilder.forSecondary(context, KafkaService.class)
                        .withName(KAFKA_SERVICES)
                        .withRelationship(Ref.toSecondaries(KafkaProtocolRouterReconciler::kafkaServiceTargetRefs))
                        .build(),
                EventSourceBuilder.forSecondary(context, KafkaProtocolRouter.class)
                        .withName(ROUTER_TARGETS)
                        .withRelationship(Ref.toSecondaries(KafkaProtocolRouterReconciler::routerTargetRefs))
                        .build(),
                EventSourceBuilder.forSecondary(context, KafkaProtocolFilter.class)
                        .withName(FILTER_REFS)
                        .withRelationship(Ref.toSecondaries(KafkaProtocolRouterReconciler::routeFilterRefs))
                        .build());
    }

    private PrimaryToSecondaryMapper<KafkaProtocolRouter> configTemplateSecretMapper() {
        return router -> {
            var interpolationResult = secureConfigInterpolator.interpolate(router.getSpec().getConfigTemplate());
            return secretNamesFromVolumes(interpolationResult)
                    .map(n -> new ResourceID(n, ResourcesUtil.namespace(router)))
                    .collect(Collectors.toSet());
        };
    }

    private SecondaryToPrimaryMapper<Secret> configTemplateSecretSecondaryMapper(EventSourceContext<KafkaProtocolRouter> context) {
        return secondary -> ResourcesUtil.filteredResourceIdsInSameNamespace(
                context, secondary, KafkaProtocolRouter.class,
                router -> {
                    var ir = secureConfigInterpolator.interpolate(router.getSpec().getConfigTemplate());
                    return secretNamesFromVolumes(ir).anyMatch(n -> n.equals(ResourcesUtil.name(secondary)));
                });
    }

    private PrimaryToSecondaryMapper<KafkaProtocolRouter> configTemplateConfigMapMapper() {
        return router -> {
            var interpolationResult = secureConfigInterpolator.interpolate(router.getSpec().getConfigTemplate());
            return configMapNamesFromVolumes(interpolationResult)
                    .map(n -> new ResourceID(n, ResourcesUtil.namespace(router)))
                    .collect(Collectors.toSet());
        };
    }

    private SecondaryToPrimaryMapper<ConfigMap> configTemplateConfigMapSecondaryMapper(EventSourceContext<KafkaProtocolRouter> context) {
        return secondary -> ResourcesUtil.filteredResourceIdsInSameNamespace(
                context, secondary, KafkaProtocolRouter.class,
                router -> {
                    var ir = secureConfigInterpolator.interpolate(router.getSpec().getConfigTemplate());
                    return configMapNamesFromVolumes(ir).anyMatch(n -> n.equals(ResourcesUtil.name(secondary)));
                });
    }

    private static Stream<String> secretNamesFromVolumes(SecureConfigInterpolator.InterpolationResult result) {
        return result.volumes().stream()
                .flatMap(volume -> Optional.ofNullable(volume.getSecret())
                        .map(SecretVolumeSource::getSecretName)
                        .stream());
    }

    private static Stream<String> configMapNamesFromVolumes(SecureConfigInterpolator.InterpolationResult result) {
        return result.volumes().stream()
                .flatMap(volume -> Optional.ofNullable(volume.getConfigMap())
                        .map(ConfigMapVolumeSource::getName)
                        .stream());
    }

    @SuppressWarnings("unchecked")
    private static Collection<LocalRef<KafkaService>> kafkaServiceTargetRefs(KafkaProtocolRouter router) {
        return routes(router)
                .map(Routes::getTargetRef)
                .filter(ref -> KAFKA_SERVICE_KIND.equals(ref.getKind()))
                .map(ref -> (LocalRef<KafkaService>) (LocalRef<?>) ref)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Collection<LocalRef<KafkaProtocolRouter>> routerTargetRefs(KafkaProtocolRouter router) {
        return routes(router)
                .map(Routes::getTargetRef)
                .filter(ref -> KAFKA_PROTOCOL_ROUTER_KIND.equals(ref.getKind()))
                .map(ref -> (LocalRef<KafkaProtocolRouter>) (LocalRef<?>) ref)
                .toList();
    }

    private static Collection<FilterRef> routeFilterRefs(KafkaProtocolRouter router) {
        return routes(router)
                .flatMap(route -> Optional.ofNullable(route.getFilterRefs()).orElse(List.of()).stream())
                .toList();
    }

    private static Stream<Routes> routes(KafkaProtocolRouter router) {
        return Optional.ofNullable(router.getSpec().getRoutes()).orElse(List.of()).stream();
    }

    @Override
    public UpdateControl<KafkaProtocolRouter> reconcile(KafkaProtocolRouter router, Context<KafkaProtocolRouter> context) {

        Map<String, KafkaService> existingKafkaServicesByName = context.getSecondaryResourcesAsStream(KafkaService.class).collect(toByNameMap());
        Map<String, KafkaProtocolRouter> existingRoutersByName = context.getSecondaryResourcesAsStream(KafkaProtocolRouter.class).collect(toByNameMap());
        Map<String, KafkaProtocolFilter> existingFiltersByName = context.getSecondaryResourcesAsStream(KafkaProtocolFilter.class).collect(toByNameMap());
        Map<String, Secret> existingSecretsByName = context.getSecondaryResourcesAsStream(Secret.class).collect(toByNameMap());
        Map<String, ConfigMap> existingConfigMapsByName = context.getSecondaryResourcesAsStream(ConfigMap.class).collect(toByNameMap());

        var routesList = Optional.ofNullable(router.getSpec().getRoutes()).orElse(List.of());

        var declaredKafkaServiceNames = routesList.stream()
                .map(Routes::getTargetRef)
                .filter(ref -> KAFKA_SERVICE_KIND.equals(ref.getKind()))
                .map(ref -> ref.getName())
                .collect(Collectors.toCollection(TreeSet::new));

        var declaredRouterTargetNames = routesList.stream()
                .map(Routes::getTargetRef)
                .filter(ref -> KAFKA_PROTOCOL_ROUTER_KIND.equals(ref.getKind()))
                .map(ref -> ref.getName())
                .collect(Collectors.toCollection(TreeSet::new));

        var declaredFilterNames = routesList.stream()
                .flatMap(route -> Optional.ofNullable(route.getFilterRefs()).orElse(List.of()).stream())
                .map(FilterRef::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        var interpolationResult = secureConfigInterpolator.interpolate(router.getSpec().getConfigTemplate());
        var declaredSecretNames = secretNamesFromVolumes(interpolationResult)
                .collect(Collectors.toCollection(TreeSet::new));
        var declaredConfigMapNames = configMapNamesFromVolumes(interpolationResult)
                .collect(Collectors.toCollection(TreeSet::new));

        var missingKafkaServices = new TreeSet<>(declaredKafkaServiceNames);
        missingKafkaServices.removeAll(existingKafkaServicesByName.keySet());

        var missingRouterTargets = new TreeSet<>(declaredRouterTargetNames);
        missingRouterTargets.removeAll(existingRoutersByName.keySet());

        var missingFilters = new TreeSet<>(declaredFilterNames);
        missingFilters.removeAll(existingFiltersByName.keySet());

        var missingSecrets = new TreeSet<>(declaredSecretNames);
        missingSecrets.removeAll(existingSecretsByName.keySet());

        var missingConfigMaps = new TreeSet<>(declaredConfigMapNames);
        missingConfigMaps.removeAll(existingConfigMapsByName.keySet());

        KafkaProtocolRouter patch;
        if (!missingKafkaServices.isEmpty() || !missingRouterTargets.isEmpty() || !missingFilters.isEmpty()) {
            String message = buildRouteRefsMissingMessage(missingKafkaServices, missingRouterTargets, missingFilters);
            patch = statusFactory.newFalseConditionStatusPatch(router, Condition.Type.ResolvedRefs, Condition.REASON_REFS_NOT_FOUND, message);
        }
        else if (!missingSecrets.isEmpty() || !missingConfigMaps.isEmpty()) {
            String message = buildInterpolatedRefsMissingMessage(missingSecrets, missingConfigMaps);
            patch = statusFactory.newFalseConditionStatusPatch(router, Condition.Type.ResolvedRefs, Condition.REASON_INTERPOLATED_REFS_NOT_FOUND, message);
        }
        else {
            var checksumGenerator = new Crc32ChecksumGenerator();
            Stream<HasMetadata> referents = Stream.of(
                    declaredKafkaServiceNames.stream().map(existingKafkaServicesByName::get),
                    declaredRouterTargetNames.stream().map(existingRoutersByName::get),
                    declaredFilterNames.stream().map(existingFiltersByName::get),
                    declaredSecretNames.stream().map(existingSecretsByName::get),
                    declaredConfigMapNames.stream().map(existingConfigMapsByName::get))
                    .flatMap(s -> s);
            referents.forEachOrdered(checksumGenerator::appendMetadata);
            patch = statusFactory.newTrueConditionStatusPatch(router, Condition.Type.ResolvedRefs, checksumGenerator.encode());
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.atInfo()
                    .addKeyValue(OperatorLoggingKeys.NAMESPACE, namespace(router))
                    .addKeyValue(OperatorLoggingKeys.NAME, name(router))
                    .log("Completed reconciliation");
        }
        return UpdateControl.patchResourceAndStatus(patch);
    }

    private static String buildRouteRefsMissingMessage(Set<String> missingKafkaServices,
                                                       Set<String> missingRouterTargets,
                                                       Set<String> missingFilters) {
        StringBuilder sb = new StringBuilder("Referenced");
        if (!missingKafkaServices.isEmpty()) {
            sb.append(" KafkaServices ").append(missingKafkaServices);
        }
        if (!missingRouterTargets.isEmpty()) {
            sb.append(" KafkaProtocolRouters ").append(missingRouterTargets);
        }
        if (!missingFilters.isEmpty()) {
            sb.append(" KafkaProtocolFilters ").append(missingFilters);
        }
        sb.append(" not found");
        return sb.toString();
    }

    private static String buildInterpolatedRefsMissingMessage(Set<String> missingSecrets, Set<String> missingConfigMaps) {
        String message = "Referenced";
        if (!missingSecrets.isEmpty()) {
            message += " Secrets [" + String.join(", ", missingSecrets) + "]";
        }
        if (!missingConfigMaps.isEmpty()) {
            message += " ConfigMaps [" + String.join(", ", missingConfigMaps) + "]";
        }
        message += " not found";
        return message;
    }

    @Override
    public ErrorStatusUpdateControl<KafkaProtocolRouter> updateErrorStatus(KafkaProtocolRouter router,
                                                                           Context<KafkaProtocolRouter> context,
                                                                           Exception e) {
        ErrorStatusUpdateControl<KafkaProtocolRouter> uc = ErrorStatusUpdateControl
                .patchStatus(statusFactory.newUnknownConditionStatusPatch(router, Condition.Type.ResolvedRefs, e));
        if (LOGGER.isInfoEnabled()) {
            LOGGER.atInfo()
                    .addKeyValue(OperatorLoggingKeys.NAMESPACE, namespace(router))
                    .addKeyValue(OperatorLoggingKeys.NAME, name(router))
                    .addKeyValue(OperatorLoggingKeys.ERROR, e.toString())
                    .log("Completed reconciliation with error");
        }
        return uc;
    }
}
