/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import org.apache.kafka.common.protocol.Errors;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Internal carrier used where a {@link Throwable} is genuinely required (for example to
 * complete a request promise exceptionally) but the error to relay to the client is
 * modelled as an {@link Errors} code plus an optional message.
 * <p>
 * This deliberately does <em>not</em> extend Kafka's {@code ApiException}: the runtime
 * represents client-facing errors as an {@link Errors} code and message, and only
 * materialises a {@link Throwable} at the points that require one.
 */
public class KafkaErrorException extends RuntimeException {

    private final Errors error;

    /**
     * Creates the exception.
     *
     * @param error the error code to relay to the client
     * @param message the detail message to relay to the client, or {@code null} to use the error's default message
     */
    public KafkaErrorException(Errors error, @Nullable String message) {
        super(message != null ? message : error.message());
        this.error = error;
    }

    /**
     * Returns the error code to relay to the client.
     * @return the error code
     */
    public Errors error() {
        return error;
    }
}
