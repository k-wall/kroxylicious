/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

/**
 * This class exists to configure Sundrio so that builders are generated for the configuration model.
 */
public final class BuilderConfig {
    public static final String TARGET_CONFIG_PACKAGE = "io.kroxylicious.proxy.config";

    private BuilderConfig() {
        throw new IllegalStateException();
    }

}
