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

public final class NetworkBindRequest extends NetworkBindingOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkBindRequest.class);

    public NetworkBindRequest(int port, boolean tls, CompletableFuture<Channel> future) {
        super(port, tls, future);
    }

    public static NetworkBindRequest createNetworkBindRequest(Endpoint key, boolean useTls) {
        return new NetworkBindRequest(key.port(), useTls, new CompletableFuture<>());
    }

    @Override
    public void performBindingOperation(ServerBootstrap serverBootstrap) {

        int port = port();
        LOGGER.info("Binding :{}", port);
        serverBootstrap.bind(port).addListener((ChannelFutureListener) channelFuture -> getCompletionStage().toCompletableFuture().complete(channelFuture.channel()));
    }

}
