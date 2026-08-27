/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Errors;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * In the operation of the proxy there are various error conditions which are "anticipated" but not necessarily handled directly.
 * SSL Handshake errors are an illustrative example, where they are thrown and propagate through the netty channel without being handled.
 * <p>
 * The exception mapper provides a mechanism to build a Kafka error response, carrying an {@link Errors} code and an
 * optional message, to respond to the client with.
 */
public class KafkaProxyExceptionMapper {

    private KafkaProxyExceptionMapper() {
    }

    /**
     * Builds the body of an error response answering the given request frame, with the error code
     * set according to the given error.
     * @param frame the request frame being answered
     * @param error the error to convey to the client
     * @param message the error message to convey to the client, or {@code null} to use the error's default message
     * @return the error response body, or {@code null} if the request expects no response (e.g. Produce with acks=0)
     */
    @Nullable
    public static ApiMessage errorResponseMessage(DecodedRequestFrame<?> frame, Errors error, @Nullable String message) {
        return ErrorResponseFactory.errorResponseData(frame.apiKey(), frame.body(), frame.apiVersion(), error, message);
    }

    /**
     * Builds an error response answering the given request message, with the error code set
     * according to the given error.
     * @param requestHeaders the headers of the request being answered
     * @param message the body of the request being answered
     * @param error the error to convey to the client
     * @param errorMessage the error message to convey to the client, or {@code null} to use the error's default message
     * @return the error response, or {@code null} if the request expects no response (e.g. Produce with acks=0)
     */
    @Nullable
    public static ApiMessage errorResponseForMessage(RequestHeaderData requestHeaders, ApiMessage message, Errors error, @Nullable String errorMessage) {
        ApiKeys apiKey = ApiKeys.forId(message.apiKey());
        return ErrorResponseFactory.errorResponseData(apiKey, message, requestHeaders.requestApiVersion(), error, errorMessage);
    }
}
