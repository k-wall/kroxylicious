/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.kubernetes.operator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.ManagedDependentResourceContext;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.event.ResourceID;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaClusterRef;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaClusterSpec;
import io.kroxylicious.kubernetes.api.v1alpha1.virtualkafkaclusterspec.Filters;
import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.config.NamedFilterDefinition;
import io.kroxylicious.proxy.config.PortIdentifiesNodeIdentificationStrategy;
import io.kroxylicious.proxy.config.TargetCluster;
import io.kroxylicious.proxy.config.VirtualCluster;
import io.kroxylicious.proxy.config.VirtualClusterGateway;
import io.kroxylicious.proxy.config.admin.AdminHttpConfiguration;
import io.kroxylicious.proxy.config.admin.EndpointsConfiguration;
import io.kroxylicious.proxy.config.admin.PrometheusMetricsConfig;
import io.kroxylicious.proxy.service.HostPort;

import edu.umd.cs.findbugs.annotations.NonNull;

import static io.kroxylicious.kubernetes.operator.Labels.standardLabels;

/**
 * Generates a Kube {@code Secret} containing the proxy config YAML.
 * We use a {@code Secret} (rather than a {@code ConfigMap})
 * because the config might contain sensitive settings like passwords
 */
@KubernetesDependent
public class ProxyConfigSecret
        extends CRUDKubernetesDependentResource<Secret, KafkaProxy> {

    /**
     * The key of the {@code config.yaml} entry in the desired {@code Secret}.
     */
    public static final String CONFIG_YAML_KEY = "proxy-config.yaml";

    public static final String SECURE_VOLUME_KEY = "secure-volumes";
    public static final String SECURE_VOLUME_MOUNT_KEY = "secure-volume-mounts";

    private static final ObjectMapper OBJECT_MAPPER = ConfigParser.createObjectMapper();

    private static String toYaml(Object filterDefs) {
        try {
            return OBJECT_MAPPER.writeValueAsString(filterDefs);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final SecureConfigInterpolator secureConfigInterpolator;

    public ProxyConfigSecret() {
        super(Secret.class);
        var providerMap = Map.<String, SecureConfigProvider> of(
                "secret", MountedResourceConfigProvider.SECRET_PROVIDER,
                "configmap", MountedResourceConfigProvider.CONFIGMAP_PROVIDER);
        secureConfigInterpolator = new SecureConfigInterpolator("/opt/kroxylicious/secure", providerMap);
    }

    /**
     * @return The {@code metadata.name} of the desired Secret {@code Secret}.
     */
    static String secretName(KafkaProxy primary) {
        return primary.getMetadata().getName();
    }

    public static List<Volume> secureVolumes(ManagedDependentResourceContext managedDependentResourceContext) {
        Set<Volume> volumes = managedDependentResourceContext.get(ProxyConfigSecret.SECURE_VOLUME_KEY, Set.class).orElse(Set.of());
        if (volumes.stream().map(Volume::getName).distinct().count() != volumes.size()) {
            throw new IllegalStateException("Two volumes with different definitions share the same name");
        }
        return volumes.stream().toList();
    }

    public static List<VolumeMount> secureVolumeMounts(ManagedDependentResourceContext managedDependentResourceContext) {
        Set<VolumeMount> mounts = managedDependentResourceContext.get(ProxyConfigSecret.SECURE_VOLUME_MOUNT_KEY, Set.class).orElse(Set.of());
        if (mounts.stream().map(VolumeMount::getMountPath).distinct().count() != mounts.size()) {
            throw new IllegalStateException("Two volume mounts with different definitions share the same mount path");
        }
        return mounts.stream().toList();
    }

    @Override
    protected Secret desired(KafkaProxy primary,
                             Context<KafkaProxy> context) {
        // @formatter:off
        return new SecretBuilder()
                .editOrNewMetadata()
                    .withName(secretName(primary))
                    .withNamespace(primary.getMetadata().getNamespace())
                    .addToLabels(standardLabels(primary))
                    .addNewOwnerReferenceLike(ResourcesUtil.ownerReferenceTo(primary)).endOwnerReference()
                .endMetadata()
                .withStringData(Map.of(CONFIG_YAML_KEY, generateProxyConfig(primary, context)))
                .build();
        // @formatter:on
    }

    String generateProxyConfig(KafkaProxy primary,
                               Context<KafkaProxy> context) {

        List<VirtualKafkaCluster> virtualKafkaClusters = ResourcesUtil.clustersInNameOrder(context).toList();

        Map<ResourceID, KafkaClusterRef> clusterRefs = ResourcesUtil.clusterRefs(context);

        List<NamedFilterDefinition> filterDefinitions = buildFilterDefinitions(context, virtualKafkaClusters);

        var virtualClusters = buildVirtualClusters(primary, context, virtualKafkaClusters, clusterRefs);

        Configuration configuration = new Configuration(
                new AdminHttpConfiguration(null, null, new EndpointsConfiguration(new PrometheusMetricsConfig())), filterDefinitions,
                null, // no defaultFilters <= each of the virtualClusters specifies its own
                virtualClusters,
                List.of(), false,
                // micrometer
                Optional.empty());

        return toYaml(configuration);
    }

    @NonNull
    private static List<VirtualCluster> buildVirtualClusters(KafkaProxy primary, Context<KafkaProxy> context, List<VirtualKafkaCluster> clusters,
                                                             Map<ResourceID, KafkaClusterRef> clusterRefs) {
        AtomicInteger clusterNum = new AtomicInteger(0);
        return clusters.stream()
                .filter(cluster -> !SharedKafkaProxyContext.isBroken(context, cluster))
                .map(cluster -> getVirtualCluster(primary, cluster, clusterNum.getAndIncrement(), clusterRefs))
                .toList();
    }

    @NonNull

    private List<NamedFilterDefinition> buildFilterDefinitions(Context<KafkaProxy> context, List<VirtualKafkaCluster> clusters) {
        List<NamedFilterDefinition> filterDefinitions = new ArrayList<>();
        Set<NamedFilterDefinition> uniqueValues = new HashSet<>();
        for (VirtualKafkaCluster cluster1 : clusters) {
            try {
                for (NamedFilterDefinition namedFilterDefinition : filterDefinitions(context, cluster1)) {
                    if (uniqueValues.add(namedFilterDefinition)) {
                        filterDefinitions.add(namedFilterDefinition);
                    }
                }
            }
            catch (InvalidClusterException e) {
                SharedKafkaProxyContext.addClusterCondition(context, cluster1, e.accepted());
            }
        }
        filterDefinitions.sort(Comparator.comparing(NamedFilterDefinition::name));
        return filterDefinitions;
    }

    private static List<String> filterNamesForCluster(VirtualKafkaCluster cluster) {
        return Optional.ofNullable(cluster.getSpec().getFilters())
                .orElse(List.of())
                .stream()
                .map(ProxyConfigSecret::filterDefinitionName)
                .toList();
    }

    @NonNull
    private static String filterDefinitionName(Filters filterCrRef) {
        return filterCrRef.getName() + "." + filterCrRef.getKind() + "." + filterCrRef.getGroup();
    }

    @NonNull
    private List<NamedFilterDefinition> filterDefinitions(Context<KafkaProxy> context, VirtualKafkaCluster cluster)
            throws InvalidClusterException {

        return Optional.ofNullable(cluster.getSpec().getFilters()).orElse(List.of()).stream().map(filterCrRef -> {

            String filterDefinitionName = filterDefinitionName(filterCrRef);

            var filterCr = filterResourceFromRef(cluster, context, filterCrRef);
            if (filterCr.getAdditionalProperties().get("spec") instanceof Map<?, ?> spec) {
                String type = (String) spec.get("type");
                SecureConfigInterpolator.InterpolationResult interpolationResult = interpolateConfig(spec);
                var ctx = context.managedDependentResourceContext();
                putOrMerged(ctx, SECURE_VOLUME_KEY, interpolationResult.volumes());
                putOrMerged(ctx, SECURE_VOLUME_MOUNT_KEY, interpolationResult.mounts());
                return new NamedFilterDefinition(filterDefinitionName, type, interpolationResult.config());
            }
            else {
                throw new InvalidClusterException(ClusterCondition.filterInvalid(cluster.getMetadata().getName(), filterDefinitionName, "`spec` was not an `object`."));
            }

        }).toList();
    }

    private static <T> void putOrMerged(ManagedDependentResourceContext ctx, String ctxKey, Set<T> set) {
        Optional<Set<T>> ctxVolumes = (Optional) ctx.get(ctxKey, Set.class);
        if (ctxVolumes.isPresent()) {
            ctxVolumes.get().addAll(set);
        }
        else {
            ctx.put(ctxKey, new LinkedHashSet<>(set));
        }
    }

    private @NonNull SecureConfigInterpolator.InterpolationResult interpolateConfig(Map<?, ?> spec) {
        SecureConfigInterpolator.InterpolationResult result;
        Object configTemplate = spec.get("configTemplate");
        if (configTemplate != null) {
            result = secureConfigInterpolator.interpolate(configTemplate);
        }
        else {
            result = new SecureConfigInterpolator.InterpolationResult(spec.get("config"), Set.of(), Set.of());
        }
        return result;
    }

    @NonNull
    private static InvalidClusterException resourceNotFound(VirtualKafkaCluster cluster, Filters filterRef) {
        return new InvalidClusterException(ClusterCondition.filterNotExists(cluster.getMetadata().getName(), filterRef.getName()));
    }

    /**
     * Look up a Filter CR from the group, kind and name given in the cluster.
     */
    @NonNull
    private static GenericKubernetesResource filterResourceFromRef(VirtualKafkaCluster cluster, Context<KafkaProxy> context, Filters filterRef)
            throws InvalidClusterException {
        return context.getSecondaryResources(GenericKubernetesResource.class).stream()
                .filter(filterResource -> {
                    String apiVersion = filterResource.getApiVersion();
                    var filterResourceGroup = apiVersion.substring(0, apiVersion.indexOf("/"));
                    return filterResourceGroup.equals(filterRef.getGroup())
                            && filterResource.getKind().equals(filterRef.getKind())
                            && filterResource.getMetadata().getName().equals(filterRef.getName());
                })
                .findFirst()
                .orElseThrow(() -> resourceNotFound(cluster, filterRef));
    }

    private static VirtualCluster getVirtualCluster(KafkaProxy primary,
                                                    VirtualKafkaCluster cluster,
                                                    int clusterNum, Map<ResourceID, KafkaClusterRef> clusterRefs) {

        var kafkaClusterRef = clusterTargetClusterResourceID(cluster).map(clusterRefs::get);

        if (kafkaClusterRef.isEmpty()) {
            // I should be a condition
            throw new IllegalStateException("boom!");
        }

        String bootstrap = kafkaClusterRef.get().getSpec().getBootstrapServers();
        return new VirtualCluster(
                cluster.getMetadata().getName(), new TargetCluster(bootstrap, Optional.empty()),
                null,
                Optional.empty(),
                List.of(new VirtualClusterGateway("default",
                        new PortIdentifiesNodeIdentificationStrategy(new HostPort("localhost", 9292 + (100 * clusterNum)),
                                ClusterService.absoluteServiceHost(primary, cluster), null, null),
                        null,
                        Optional.empty())),
                false, false,
                filterNamesForCluster(cluster));
    }

    private static Optional<ResourceID> clusterTargetClusterResourceID(VirtualKafkaCluster cluster) {
        var namespace = cluster.getMetadata().getNamespace();
        return Optional.ofNullable(cluster.getSpec())
                .map(VirtualKafkaClusterSpec::getTargetCluster)
                .map(io.kroxylicious.kubernetes.api.v1alpha1.virtualkafkaclusterspec.TargetCluster::getClusterRef)
                .map(r -> new ResourceID(r.getName(), namespace));
    }
}
