/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config.admin;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.sundr.builder.annotations.Buildable;

import io.kroxylicious.proxy.config.BuilderConfig;

public record AdminHttpConfiguration(String host, Integer port, EndpointsConfiguration endpoints) {
    @Buildable(editableEnabled = false, generateBuilderPackage = true, builderPackage = BuilderConfig.TARGET_CONFIG_PACKAGE)
    @JsonCreator
    public AdminHttpConfiguration(String host, Integer port, EndpointsConfiguration endpoints) {
        this.host = host == null ? "0.0.0.0" : host;
        this.port = port == null ? 9193 : port;
        this.endpoints = endpoints == null ? new EndpointsConfiguration(null) : endpoints;
    }
}
