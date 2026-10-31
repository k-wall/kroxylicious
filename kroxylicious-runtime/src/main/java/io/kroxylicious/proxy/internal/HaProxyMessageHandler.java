/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.haproxy.HAProxyMessage;

import edu.umd.cs.findbugs.annotations.Nullable;

import io.kroxylicious.proxy.internal.net.HaProxyContext;

/**
 * A channel handler that intercepts {@link HAProxyMessage} objects emitted by
 * Netty's {@link io.netty.handler.codec.haproxy.HAProxyMessageDecoder} and
 * stores the extracted context in the {@link KafkaSession}.
 * <p>
 * This handler prevents {@link HAProxyMessage} from propagating further down
 * the pipeline to handlers (like {@link FilterHandler}) that only expect
 * Kafka protocol messages. Extending {@link SimpleChannelInboundHandler} ensures
 * the ref-counted {@link HAProxyMessage} is released automatically after
 * {@link #channelRead0} returns; all other messages are forwarded unchanged.
 * </p>
 * <p>
 * An optional {@code postProxyBytes} buffer may be supplied when the detection
 * handler knows that bytes following the PROXY header (e.g. a TLS ClientHello)
 * arrived in the same TCP segment. Those bytes are fired downstream after the
 * PROXY context is stored, so they reach {@code SniHandler} without passing
 * through {@code HAProxyMessageDecoder} (which would misinterpret them).
 * </p>
 */
public class HaProxyMessageHandler extends SimpleChannelInboundHandler<HAProxyMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HaProxyMessageHandler.class);

    private final KafkaSession kafkaSession;
    @Nullable
    private ByteBuf postProxyBytes;

    /**
     * Creates a handler that stores decoded PROXY-protocol messages in the given session.
     * @param kafkaSession the session in which the extracted {@link HaProxyContext} is stored
     */
    public HaProxyMessageHandler(KafkaSession kafkaSession) {
        this(kafkaSession, null);
    }

    /**
     * Creates a handler that stores decoded PROXY-protocol messages and forwards trailing bytes.
     * @param kafkaSession  the session in which the extracted {@link HaProxyContext} is stored
     * @param postProxyBytes bytes that arrived in the same TCP segment after the PROXY header,
     *                       to be forwarded downstream after the context is stored; may be null
     */
    HaProxyMessageHandler(KafkaSession kafkaSession, @Nullable ByteBuf postProxyBytes) {
        this.kafkaSession = kafkaSession;
        this.postProxyBytes = postProxyBytes;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HAProxyMessage haProxyMessage) {
        LOGGER.atDebug()
                .addKeyValue("channelId", () -> ctx.channel().toString())
                .addKeyValue("sourceAddress", haProxyMessage.sourceAddress())
                .addKeyValue("sourcePort", haProxyMessage.sourcePort())
                .addKeyValue("destinationAddress", haProxyMessage.destinationAddress())
                .addKeyValue("destinationPort", haProxyMessage.destinationPort())
                .log("Received HAProxy message");
        kafkaSession.setHaProxyContext(HaProxyContext.from(haProxyMessage));
        if (postProxyBytes != null) {
            var toFire = postProxyBytes;
            postProxyBytes = null;
            ctx.fireChannelRead(toFire);
        }
        ctx.pipeline().remove(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (postProxyBytes != null) {
            postProxyBytes.release();
            postProxyBytes = null;
        }
        super.channelInactive(ctx);
    }
}
