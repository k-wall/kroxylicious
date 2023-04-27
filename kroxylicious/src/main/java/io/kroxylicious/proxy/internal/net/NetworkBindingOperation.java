/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.netty.channel.Channel;

public abstract class NetworkBindingOperation {

    private final int port;
    private final boolean tls;

    private final CompletableFuture<Channel> channelFuture;

    protected NetworkBindingOperation(int port, boolean tls, CompletableFuture<Channel> channelFuture) {
        this.port = port;
        this.tls = tls;
        this.channelFuture = channelFuture;
    }

    public int port() {
        return port;
    }

    public boolean tls() {
        return tls;
    }

    public CompletionStage<Channel> getCompletionStage() {
        return channelFuture;
    }

    public void complete(Channel value) {
        channelFuture.complete(value);
    }

    public void completeExceptionally(Throwable t) {
        channelFuture.completeExceptionally(t);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        var that = (NetworkBindingOperation) obj;
        return this.port == that.port &&
                this.tls == that.tls;
    }

    @Override
    public int hashCode() {
        return Objects.hash(port, tls);
    }

    @Override
    public String toString() {
        return this.getClass() + "[" +
                "port=" + port + ", " +
                "tls=" + tls + ']';
    }
}
