/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

public class NetworkUnbindRequest extends NetworkBindingOperation<Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkUnbindRequest.class);
    private final Channel channel;
    private final CompletableFuture<Void> future;

    public NetworkUnbindRequest(boolean tls, Channel channel, CompletableFuture<Void> future) {
        super(tls);
        this.channel = channel;
        this.future = future;
    }

    @Override
    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void performBindingOperation(ServerBootstrap serverBootstrap) {
        var addr = channel.localAddress();
        LOGGER.info("Unbinding {}", addr);

        channel.close().addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.cause() != null) {
                future.completeExceptionally(channelFuture.cause());
            }
            else {
                future.complete(null);
            }
        });
    }

    @Override
    public CompletableFuture<Void> getFuture() {
        return future;
    }
}
