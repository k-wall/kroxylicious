/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.internal.net.HaProxyContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class HaProxyMessageHandlerTest {

    // PROXY v1 header matching the V2 fields used in ProxyProtocolIT for parity
    private static final String PROXY_V1_HEADER = "PROXY TCP4 192.168.1.100 10.0.0.1 54321 9092\r\n";

    @Mock
    private KafkaSession kafkaSession;

    private HaProxyMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HaProxyMessageHandler(kafkaSession);
    }

    @Test
    void shouldStoreHaProxyContextInSession() {
        // Given
        var embeddedChannel = new EmbeddedChannel(new HAProxyMessageDecoder(), handler);

        // When
        embeddedChannel.writeInbound(Unpooled.copiedBuffer(PROXY_V1_HEADER, StandardCharsets.US_ASCII));

        // Then
        verify(kafkaSession).setHaProxyContext(any(HaProxyContext.class));
        verifyNoMoreInteractions(kafkaSession);
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    void shouldRemoveDecoderAndSelfFromPipelineAfterProcessing() {
        // Given
        var embeddedChannel = new EmbeddedChannel(new HAProxyMessageDecoder(), handler);

        // When
        embeddedChannel.writeInbound(Unpooled.copiedBuffer(PROXY_V1_HEADER, StandardCharsets.US_ASCII));

        // Then - both handlers removed so subsequent bytes reach the next handler unmodified
        assertThat(embeddedChannel.pipeline().get(HaProxyMessageHandler.class)).isNull();
        assertThat(embeddedChannel.pipeline().get(HAProxyMessageDecoder.class)).isNull();
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    void shouldNotPropagateHaProxyMessageDownPipeline() {
        // Given
        var embeddedChannel = new EmbeddedChannel(new HAProxyMessageDecoder(), handler);

        // When
        embeddedChannel.writeInbound(Unpooled.copiedBuffer(PROXY_V1_HEADER, StandardCharsets.US_ASCII));

        // Then - HAProxyMessage must not be passed to downstream Kafka handlers
        assertThat((Object) embeddedChannel.readInbound()).isNull();
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    void shouldForwardPostProxyBytesToNextHandler() {
        // Given
        var postProxyData = Unpooled.copiedBuffer("extra-bytes", StandardCharsets.US_ASCII);
        handler = new HaProxyMessageHandler(kafkaSession, postProxyData);
        var embeddedChannel = new EmbeddedChannel(new HAProxyMessageDecoder(), handler);

        // When
        embeddedChannel.writeInbound(Unpooled.copiedBuffer(PROXY_V1_HEADER, StandardCharsets.US_ASCII));

        // Then - postProxyBytes are forwarded downstream (readable from the channel's inbound queue)
        ByteBuf forwarded = embeddedChannel.readInbound();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.toString(StandardCharsets.US_ASCII)).isEqualTo("extra-bytes");
        forwarded.release();
        embeddedChannel.finishAndReleaseAll();
    }

    @Test
    void shouldPassThroughNonHAProxyMessages() throws Exception {
        // Given
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        DecodedRequestFrame<?> kafkaFrame = mock(DecodedRequestFrame.class);

        // When
        handler.channelRead(ctx, kafkaFrame);

        // Then
        verifyNoInteractions(kafkaSession);
        verify(ctx).fireChannelRead(kafkaFrame);
    }

    @Test
    void shouldPassThroughOtherObjectTypes() throws Exception {
        // Given
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Object someOtherMessage = new Object();

        // When
        handler.channelRead(ctx, someOtherMessage);

        // Then
        verifyNoInteractions(kafkaSession);
        verify(ctx).fireChannelRead(someOtherMessage);
    }
}
