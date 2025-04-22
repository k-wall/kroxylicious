/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.model.ingress;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;

import io.kroxylicious.kubernetes.api.common.AnyLocalRef;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyIngress;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.NodeIdRanges;
import io.kroxylicious.kubernetes.api.v1alpha1.virtualkafkaclusterspec.Ingresses;
import io.kroxylicious.kubernetes.operator.ConfigurationFragment;
import io.kroxylicious.kubernetes.operator.ProxyDeploymentDependentResource;
import io.kroxylicious.kubernetes.operator.ResourcesUtil;
import io.kroxylicious.proxy.config.NamedRange;
import io.kroxylicious.proxy.config.PortIdentifiesNodeIdentificationStrategy;
import io.kroxylicious.proxy.config.VirtualClusterGateway;
import io.kroxylicious.proxy.config.tls.KeyProvider;
import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.service.HostPort;
import io.kroxylicious.proxy.tag.VisibleForTesting;

import static io.kroxylicious.kubernetes.operator.Labels.standardLabels;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.name;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.namespace;
import static java.lang.Math.toIntExact;

public record ClusterIPIngressDefinition(
                                         KafkaProxyIngress resource,
                                         VirtualKafkaCluster cluster,
                                         KafkaProxy primary,
                                         List<NodeIdRanges> nodeIdRanges)
        implements IngressDefinition {

    public ClusterIPIngressDefinition {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(cluster);
        Objects.requireNonNull(primary);
        Objects.requireNonNull(nodeIdRanges);
        if (nodeIdRanges.isEmpty()) {
            throw new IllegalArgumentException("nodeIdRanges cannot be empty");
        }
    }

    public record ClusterIPIngressInstance(ClusterIPIngressDefinition definition, int firstIdentifyingPort, int lastIdentifyingPort)
            implements IngressInstance {
        public ClusterIPIngressInstance {
            Objects.requireNonNull(definition);
            sanityCheckPortRange(definition, firstIdentifyingPort, lastIdentifyingPort);
        }

        public static ConfigurationFragment<VirtualClusterGateway> gatewayConfig(ClusterIPIngressInstance instance,
                                                                                 Function<AnyLocalRef, ConfigurationFragment<Optional<KeyProvider>>> keyFunction) {
            List<NamedRange> portRanges = IntStream.range(0, instance.definition().nodeIdRanges.size()).mapToObj(i -> {
                NodeIdRanges range = instance.definition().nodeIdRanges.get(i);
                String name = Optional.ofNullable(range.getName()).orElse("range-" + i);
                return new NamedRange(name, toIntExact(range.getStart()), toIntExact(range.getEnd()));
            }).toList();

            var ingressName = instance.definition().resource.getMetadata().getName();
            var clusterWithTls = instance.definition().cluster().getSpec().getIngresses().stream()
                    .filter(i -> ingressName.equals(i.getIngressRef().getName()))
                    .map(Ingresses::getTls)
                    .filter(Objects::nonNull)
                    .findFirst();

            var keyCf = clusterWithTls.map(cwt -> keyFunction.apply(cwt.getCertificateRef()));

            var tls = keyCf.flatMap(ConfigurationFragment::fragment)
                    .map(kp -> new Tls(kp, null, null, null));
            var volumes = keyCf.map(ConfigurationFragment::volumes).orElse(Set.of());
            var mounts = keyCf.map(ConfigurationFragment::mounts).orElse(Set.of());

            return new ConfigurationFragment<>(new VirtualClusterGateway("default",
                    new PortIdentifiesNodeIdentificationStrategy(new HostPort("localhost", instance.firstIdentifyingPort),
                            instance.qualifiedServiceHost(), null,
                            portRanges),
                    null,
                    tls), volumes, mounts);
        }

        @Override
        public Stream<ServiceBuilder> services() {
            var serviceSpecBuilder = new ServiceBuilder()
                    .withNewMetadata()
                    .withName(serviceName(definition.cluster, definition.resource))
                    .withNamespace(namespace(definition.cluster))
                    .addToLabels(standardLabels(definition.primary))
                    .addNewOwnerReferenceLike(ResourcesUtil.newOwnerReferenceTo(definition.primary)).endOwnerReference()
                    .addNewOwnerReferenceLike(ResourcesUtil.newOwnerReferenceTo(definition.cluster)).endOwnerReference()
                    .addNewOwnerReferenceLike(ResourcesUtil.newOwnerReferenceTo(definition.resource)).endOwnerReference()
                    .endMetadata()
                    .withNewSpec()
                    .withSelector(ProxyDeploymentDependentResource.podLabels(definition.primary));
            for (int i = firstIdentifyingPort; i <= lastIdentifyingPort; i++) {
                serviceSpecBuilder = serviceSpecBuilder
                        .addNewPort()
                        .withName(name(definition.cluster) + "-" + i)
                        .withPort(i)
                        .withTargetPort(new IntOrString(i))
                        .withProtocol("TCP")
                        .endPort();
            }
            return Stream.of(serviceSpecBuilder.endSpec());
        }

        private static void sanityCheckPortRange(ClusterIPIngressDefinition definition, int startPortInc, int endPortInc) {
            int requiredPorts = definition.numIdentifyingPortsRequired();
            if ((endPortInc - startPortInc + 1) != requiredPorts) {
                throw new IllegalArgumentException("require " + requiredPorts + " ports");
            }
        }

        @Override
        public Stream<ContainerPort> proxyContainerPorts() {
            Stream<ContainerPort> bootstrapPort = Stream.of(new ContainerPortBuilder().withContainerPort(firstIdentifyingPort)
                    .withName(firstIdentifyingPort + "-bootstrap").build());
            Stream<ContainerPort> ingressNodePorts = IntStream.range(0, definition().nodeCount()).mapToObj(
                    nodeIdx -> {
                        int port = firstIdentifyingPort + nodeIdx + 1;
                        return new ContainerPortBuilder().withContainerPort(port)
                                .withName(port + "-node").build();
                    });
            return Stream.concat(bootstrapPort, ingressNodePorts);
        }

        String qualifiedServiceHost() {
            return name(definition.cluster) + "-" + name(definition.resource) + "." + namespace(definition.cluster) + ".svc.cluster.local";
        }
    }

    @VisibleForTesting
    public static String serviceName(VirtualKafkaCluster cluster, KafkaProxyIngress resource) {
        Objects.requireNonNull(cluster);
        Objects.requireNonNull(resource);
        return name(cluster) + "-" + name(resource);
    }

    @Override
    public ClusterIPIngressInstance createInstance(int firstIdentifyingPort, int lastIdentifyingPort) {
        return new ClusterIPIngressInstance(this, firstIdentifyingPort, lastIdentifyingPort);
    }

    @Override
    public int numIdentifyingPortsRequired() {
        // one per broker plus the bootstrap
        return nodeCount() + 1;
    }

    // note: we use CRD validation to enforce end >= start at the apiserver level
    private int nodeCount() {
        return nodeIdRanges.stream().mapToInt(range -> toIntExact((range.getEnd() - range.getStart()) + 1)).sum();
    }

}
