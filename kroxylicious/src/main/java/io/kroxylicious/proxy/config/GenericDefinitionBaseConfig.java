/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.Map;

import io.sundr.builder.annotations.Buildable;

record GenericDefinitionBaseConfig(Map<String, Object> config) implements BaseConfig {
    @Buildable(editableEnabled = false, generateBuilderPackage = true, builderPackage = BuilderConfig.TARGET_CONFIG_PACKAGE)
    GenericDefinitionBaseConfig {
    }
}
