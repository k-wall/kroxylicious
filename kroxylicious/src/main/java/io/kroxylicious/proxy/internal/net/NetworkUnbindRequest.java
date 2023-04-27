/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.concurrent.CompletableFuture;

import io.netty.channel.Channel;

public final class NetworkUnbindRequest extends NetworkBindingOperation {
    public NetworkUnbindRequest(int port, boolean tls, CompletableFuture<Channel> future) {
        super(port, tls, future);
    }

    public static NetworkUnbindRequest createNetworkUnbindRequest(Endpoint key, boolean useTls) {
        return new NetworkUnbindRequest(key.port(), useTls, new CompletableFuture<>());
    }
}
