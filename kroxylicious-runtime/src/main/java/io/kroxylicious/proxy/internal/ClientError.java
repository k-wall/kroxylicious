/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import org.apache.kafka.common.protocol.Errors;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Internal carrier for an error to be relayed to the client on connection close.
 * <p>
 * The error is modelled as an {@link Errors} code plus an optional message (rather than
 * as an {@code ApiException}). The originating {@link Throwable} {@code cause}, when one
 * exists, is retained purely for diagnostic logging and is not sent to the client.
 *
 * @param error the error code to relay to the client
 * @param message the message to relay to the client, or {@code null} to use the error's default message
 * @param cause the originating throwable, retained for logging, or {@code null} if the error was synthesized
 */
public record ClientError(Errors error, @Nullable String message, @Nullable Throwable cause) {}
