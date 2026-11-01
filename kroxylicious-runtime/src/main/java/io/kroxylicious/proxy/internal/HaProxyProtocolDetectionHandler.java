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
                int headerLen = proxyHeaderLength(cumulation);
                if (headerLen > 0 && headerLen < cumulation.readableBytes()) {
                    // The cumulation contains the complete PROXY header plus additional bytes
                    // (e.g. a TLS ClientHello that arrived in the same TCP segment). Feed only
                    // the PROXY bytes to the decoder; the rest bypass the decoder and are fired
                    // downstream by HaProxyMessageHandler once the PROXY context is stored.
                    // This prevents HAProxyMessageDecoder from attempting to parse non-PROXY
                    // bytes as a second header, which would throw HAProxyProtocolException.
                    ByteBuf proxyPart = cumulation.readRetainedSlice(headerLen);
                    ByteBuf postProxyBytes = cumulation.retain();
                    cumulation.release();
                    cumulation = null;
                    ctx.pipeline().addAfter(thisName, HAPROXY_MESSAGE_DECODER_HANDLER_NAME, new HAProxyMessageDecoder());
                    ctx.pipeline().addAfter(HAPROXY_MESSAGE_DECODER_HANDLER_NAME, HAPROXY_MESSAGE_HANDLER_NAME,
                            new HaProxyMessageHandler(kafkaSession, postProxyBytes));
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(proxyPart);
                }
                else {
                    // Either the full PROXY header length cannot yet be determined, or the
                    // cumulation contains exactly (and only) the PROXY header with nothing
                    // trailing. The standard pipeline approach is safe in both cases.
                    ctx.pipeline().addAfter(thisName, HAPROXY_MESSAGE_DECODER_HANDLER_NAME, new HAProxyMessageDecoder());
                    ctx.pipeline().addAfter(HAPROXY_MESSAGE_DECODER_HANDLER_NAME, HAPROXY_MESSAGE_HANDLER_NAME,
                            new HaProxyMessageHandler(kafkaSession));
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(cumulation);
                    cumulation = null;
                }
            }
            case INVALID -> {
                // No PROXY header detected — pass bytes through; enforcement is handled downstream
                // by ProxyProtocolEnforcementHandler based on per-gateway configuration.
                LOGGER.atDebug().addKeyValue("ctx", ctx.channel()).log("No PROXY protocol header detected, passing through");
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(cumulation);
                cumulation = null;
            }
            case NEEDS_MORE_DATA -> LOGGER.atDebug().addKeyValue("ctx", ctx.channel()).log("PROXY protocol detection needs more data, waiting for next read");
        }
    }

    /**
     * Returns the total byte length of the PROXY header starting at {@code buf}'s
     * current reader index, or {@code -1} if it cannot be determined yet (insufficient
     * bytes in the buffer). Does not modify the reader index.
     */
    private static int proxyHeaderLength(ByteBuf buf) {
        int ri = buf.readerIndex();
        int readable = buf.readableBytes();
        byte first = buf.getByte(ri);
        if (first == 0x0D) {
            // PROXY v2: 16-byte fixed header + variable-length address block.
            // Bytes 14-15 (unsigned short, big-endian) give the address block length.
            if (readable < 16) {
                return -1;
            }
            return 16 + buf.getUnsignedShort(ri + 14);
        }
        else if (first == 'P') {
            // PROXY v1: human-readable line terminated by CRLF.
            int nlPos = buf.indexOf(ri, ri + readable, (byte) '\n');
            return nlPos < 0 ? -1 : nlPos - ri + 1;
        }
        return -1;
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
