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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

public final class NetworkBindRequest extends NetworkBindingOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkBindRequest.class);
    private final String bindingAddress;

    public NetworkBindRequest(String bindingAddress, int port, boolean tls, CompletableFuture<Channel> future) {
        super(port, tls, future);
        this.bindingAddress = bindingAddress;
    }

    public static NetworkBindRequest createNetworkBindRequest(Endpoint key, boolean useTls) {
        return new NetworkBindRequest(key.bindingAddress(), key.port(), useTls, new CompletableFuture<>());
    }

    @Override
    public void performBindingOperation(ServerBootstrap serverBootstrap) {
        int port = port();
        ChannelFuture bind;
        if (bindingAddress != null) {
            LOGGER.info("Binding {}:{}", bindingAddress, port);
            bind = serverBootstrap.bind(bindingAddress, port);
        }
        else {
            LOGGER.info("Binding <any>:{}", port);
            bind = serverBootstrap.bind(port);
        }
        bind.addListener((ChannelFutureListener) channelFuture -> getCompletionStage().toCompletableFuture().complete(channelFuture.channel()));
    }

}
