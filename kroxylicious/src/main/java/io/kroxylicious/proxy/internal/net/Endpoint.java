/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

public record Endpoint(String bindingAddress, int port, boolean tls) {
    public static Endpoint createEndpoint(String bindingAddress, int port, boolean tls) {
        return new Endpoint(bindingAddress, port, tls);
    }

    public Endpoint cloneWithOverriddenAssignedPort(int actualPort) {
        if (actualPort < 1) {
            throw new IllegalArgumentException("actualPort must be greater 0");
        }
        return new Endpoint(bindingAddress(), actualPort, tls());
    }
}