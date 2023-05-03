/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

public final class NetworkUnbindRequest extends NetworkBindingOperation {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkUnbindRequest.class);
    private final Channel channel;

    public NetworkUnbindRequest(int port, boolean tls, CompletableFuture<Channel> future, Channel channel) {
        super(port, tls, future);
        this.channel = channel;
    }

    public static NetworkUnbindRequest createNetworkUnbindRequest(Endpoint key, boolean useTls, Channel channel) {
        return new NetworkUnbindRequest(key.port(), useTls, new CompletableFuture<>(), channel);
    }

    @Override
    public void performBindingOperation(ServerBootstrap serverBootstrap) {
        var addr = channel.localAddress();
        LOGGER.info("Unbinding {}", addr);

        channel.close().addListener((ChannelFutureListener) channelFuture -> getCompletionStage().toCompletableFuture().complete(channelFuture.channel()));
    }
}
