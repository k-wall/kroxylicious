/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import io.kroxylicious.proxy.config.ProxyProtocolMode;

import static io.kroxylicious.proxy.internal.util.NettyFutures.logFailure;

/**
 * Enforces the per-gateway PROXY protocol policy on the first inbound message after
 * {@link HaProxyProtocolDetectionHandler} has completed.
 * <p>
 * In {@link ProxyProtocolMode#REQUIRED} mode, the connection is closed if no PROXY header
 * was received. In {@link ProxyProtocolMode#FORBIDDEN} mode, the connection is closed if a
 * PROXY header was received. This handler removes itself from the pipeline on the first
 * passing read.
 * </p>
 */
class ProxyProtocolEnforcementHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyProtocolEnforcementHandler.class);

    private final ProxyProtocolMode mode;
    private final KafkaSession session;

    ProxyProtocolEnforcementHandler(ProxyProtocolMode mode, KafkaSession session) {
        this.mode = mode;
        this.session = session;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean hadProxy = session.haProxyContext() != null;
        if (mode == ProxyProtocolMode.REQUIRED && !hadProxy) {
            LOGGER.atWarn()
                    .addKeyValue("channel", ctx.channel())
                    .log("Connection rejected — PROXY protocol header required but not received. "
                            + "Ensure the upstream load balancer is configured to send PROXY protocol headers.");
            ReferenceCountUtil.release(msg);
            ctx.close().addListener(logFailure(LOGGER, "close after missing PROXY protocol header"));
        }
        else if (mode == ProxyProtocolMode.FORBIDDEN && hadProxy) {
            LOGGER.atWarn()
                    .addKeyValue("channel", ctx.channel())
                    .log("Connection rejected — PROXY protocol header received but this gateway forbids it. "
                            + "Disable PROXY protocol on the upstream component, or configure the gateway to require it.");
            ReferenceCountUtil.release(msg);
            ctx.close().addListener(logFailure(LOGGER, "close after forbidden PROXY protocol header"));
        }
        else {
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        }
    }
}
