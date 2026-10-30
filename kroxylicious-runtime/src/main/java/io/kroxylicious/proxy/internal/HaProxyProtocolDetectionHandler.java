/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Uses {@link HAProxyMessageDecoder#detectProtocol(ByteBuf)} on the first bytes
 * of a connection to decide whether to install the PROXY protocol decoder.
 * <p>
 * This handler is a <em>pure detector</em>: when a PROXY v1 or v2 header is found it
 * installs {@code HAProxyMessageDecoder} + {@code HaProxyMessageHandler} and stores
 * the decoded context in the {@link KafkaSession}. When no PROXY header is present the
 * bytes are passed through to the next handler unchanged.
 * </p>
 * <p>
 * Per-gateway enforcement (reject if header missing on a {@code required} gateway, or
 * reject if header present on a {@code forbidden} gateway) is handled downstream by
 * {@link ProxyProtocolEnforcementHandler}.
 * </p>
 * <p>
 * This handler removes itself from the pipeline after the first complete read.
 * </p>
 */
public class HaProxyProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HaProxyProtocolDetectionHandler.class);

    private static final String HAPROXY_MESSAGE_DECODER_HANDLER_NAME = "HAProxyMessageDecoder";
    private static final String HAPROXY_MESSAGE_HANDLER_NAME = "HaProxyMessageHandler";

    private final KafkaSession kafkaSession;
    @Nullable
    private ByteBuf cumulation;

    /**
     * Creates a PROXY protocol detection handler.
     * @param kafkaSession the session in which any decoded PROXY context will be stored
     */
    public HaProxyProtocolDetectionHandler(KafkaSession kafkaSession) {
        this.kafkaSession = kafkaSession;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }

        if (cumulation == null) {
            cumulation = buf;
        }
        else {
            if (cumulation instanceof CompositeByteBuf composite) {
                composite.addComponent(true, buf);
            }
            else {
                cumulation = ctx.alloc().compositeBuffer().addComponents(true, cumulation, buf);
            }
        }

        var result = HAProxyMessageDecoder.detectProtocol(cumulation);
        switch (result.state()) {
            case DETECTED -> {
                LOGGER.atDebug().addKeyValue("ctx", ctx.channel()).log("PROXY protocol detected, adding decoder");
                String thisName = ctx.name();
                ctx.pipeline().addAfter(thisName, HAPROXY_MESSAGE_DECODER_HANDLER_NAME, new HAProxyMessageDecoder());
                ctx.pipeline().addAfter(HAPROXY_MESSAGE_DECODER_HANDLER_NAME, HAPROXY_MESSAGE_HANDLER_NAME,
                        new HaProxyMessageHandler(kafkaSession));
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(cumulation);
                cumulation = null;
            }
            case INVALID -> {
                // No PROXY header detected — pass bytes through; enforcement is handled downstream
                // by ProxyProtocolEnforcementHandler based on per-gateway configuration.
                LOGGER.atDebug().addKeyValue("ctx", ctx.channel()).log("No PROXY protocol header detected, passing through");
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(cumulation);
                cumulation = null;
            }
            case NEEDS_MORE_DATA -> {
                LOGGER.atDebug().addKeyValue("ctx", ctx.channel()).log("PROXY protocol detection needs more data, waiting for next read");
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        super.channelInactive(ctx);
    }
}
