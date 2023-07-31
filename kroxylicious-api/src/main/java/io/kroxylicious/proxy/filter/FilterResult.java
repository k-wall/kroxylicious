/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.protocol.ApiMessage;

/**
 * The result of a filter request or response.
 */
public interface FilterResult<M extends ApiMessage, H extends ApiMessage> {
    /**
     * the header to be forwarded to the next filter in the chain.
     * @return header.
     */
    H header();

    /**
     * the message to be forwarded to the next filter in the chain.
     * @return header.
     */
    M message();

    /**
     * signals the filter's wish that the connection will be closed.
     * <br/>
     *  if the case of a {@link ResponseFilterResult}, the connection will be closed after forwarding
     *  any response in the direction of the downstream.
     * @return header.
     */
    boolean closeConnection();
}
