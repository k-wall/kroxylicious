/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.ServerBootstrap;

/**
 * Abstract encapsulation of a network binding operation.
 * @param <U> the type yielded by the future signalling the completion of the binding operation.
 */
public abstract class NetworkBindingOperation<U> {

    protected final boolean tls;

    public NetworkBindingOperation(boolean tls) {
        this.tls = tls;
    }

    public boolean tls() {
        return tls;
    }

    public abstract int port();

    public abstract CompletableFuture<U> getFuture();

    public abstract void performBindingOperation(ServerBootstrap serverBootstrap);
}
