/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;

import io.sundr.builder.annotations.Buildable;

import io.kroxylicious.proxy.micrometer.MicrometerConfigurationHookContributorManager;

public record MicrometerDefinition(@JsonProperty(required = true) String type,
                                   @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type") @JsonTypeIdResolver(MicrometerHookConfigTypeIdResolver.class) BaseConfig config) {

    private static final ObjectMapper mapper = ConfigParser.createObjectMapper();

    @JsonCreator
    public MicrometerDefinition {
        Objects.requireNonNull(type);
    }

    @Buildable(editableEnabled = false, generateBuilderPackage = true, builderPackage = BuilderConfig.TARGET_CONFIG_PACKAGE)
    protected MicrometerDefinition(BaseConfig config, String type) {
        this(type, getBaseConfig(type, config));
    }

    private static BaseConfig getBaseConfig(String type, BaseConfig config) {
        Objects.requireNonNull(type);
        if (!(config instanceof GenericDefinitionBaseConfig)) {
            throw new IllegalArgumentException("config argument must be GenericDefinitionBaseConfig");
        }
        var configType = MicrometerConfigurationHookContributorManager.getInstance().getConfigType(type);
        return mapper.convertValue(config, configType);
    }

}
