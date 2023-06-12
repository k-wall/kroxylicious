/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.sundr.builder.annotations.Buildable;

public record VirtualCluster(TargetCluster targetCluster,
                             @JsonProperty(required = true) ClusterNetworkAddressConfigProviderDefinition clusterNetworkAddressConfigProvider,
                             Optional<String> keyStoreFile,
                             Optional<String> keyPassword,
                             boolean logNetwork, boolean logFrames) {
    @Buildable(editableEnabled = false, generateBuilderPackage = true, builderPackage = BuilderConfig.TARGET_CONFIG_PACKAGE)
    @JsonCreator
    public VirtualCluster {
    }
}
