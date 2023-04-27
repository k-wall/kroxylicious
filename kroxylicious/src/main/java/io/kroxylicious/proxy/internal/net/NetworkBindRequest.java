/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.concurrent.CompletableFuture;

import io.netty.channel.Channel;

public final class NetworkBindRequest extends NetworkBindingOperation {

    public NetworkBindRequest(int port, boolean tls, CompletableFuture<Channel> future) {
        super(port, tls, future);
    }

    public static NetworkBindRequest createNetworkBindRequest(Endpoint key, boolean useTls) {
        return new NetworkBindRequest(key.port(), useTls, new CompletableFuture<>());
    }
}
