/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;

public abstract class FilterResultBuilder<B extends FilterResultBuilder<B, R, M, H>, R extends FilterResult<M, H>, M extends ApiMessage, H extends ApiMessage> {
    private H header;
    private M message;
    private boolean closeConnection;

    private FilterResultBuilder() {
    }

    public B withHeader(H header) {
        this.header = header;
        return (B) this;
    }

    H header() {
        return header;
    }

    public B withMessage(M message) {
        this.message = message;
        return (B) this;
    }

    M message() {
        return message;
    }

    public B withCloseConnection(boolean closeConnection) {
        this.closeConnection = closeConnection;
        return (B) this;
    }

    boolean closeConnection() {
        return closeConnection;
    }

    public abstract R build();

    public static ResponseFilterResultBuilder responseFilterResultBuilder() {
        return new ResponseFilterResultBuilder();
    }

    public static <M extends ApiMessage> RequestFilterResultBuilder<M> requestFilterResultBuilder() {
        return new RequestFilterResultBuilder<>();
    }

    public static class RequestFilterResultBuilder<M extends ApiMessage>
            extends FilterResultBuilder<RequestFilterResultBuilder<M>, RequestFilterResult<M>, M, RequestHeaderData> {

        private ShortCircuitResponseFilterResultBuilder shortCircuitResponseBuilder;

        public ShortCircuitResponseFilterResultBuilder withShortCircuitResponse() {
            if (shortCircuitResponseBuilder == null) {
                shortCircuitResponseBuilder = new ShortCircuitResponseFilterResultBuilder(this);
            }
            return shortCircuitResponseBuilder;
        }

        @Override
        public RequestFilterResultBuilder<M> withMessage(M message) {
            if (message != null && !message.getClass().getName().endsWith("RequestData")) {
                throw new IllegalStateException();
            }
            return super.withMessage(message);
        }

        @Override
        public RequestFilterResult<M> build() {

            // TODO: API really needs to prevent a user configuring a forward request and a short-circuit response.
            var builder = this;
            var shortCircuitResponse = shortCircuitResponseBuilder == null ? null : shortCircuitResponseBuilder.build();

            return new RequestFilterResult<M>() {
                @Override
                public RequestHeaderData header() {
                    if (shortCircuitResponse != null) {
                        throw new IllegalStateException();
                    }
                    return builder.header();
                }

                @Override
                public M message() {
                    if (shortCircuitResponse != null) {
                        throw new IllegalStateException();
                    }
                    return builder.message();
                }

                @Override
                public boolean closeConnection() {
                    if (shortCircuitResponse != null) {
                        throw new IllegalStateException();
                    }
                    return builder.closeConnection();
                }

                @Override
                public ResponseFilterResult<ApiMessage> shortCircuitResponse() {
                    return shortCircuitResponse;
                }
            };
        }
    }

    public static class ResponseFilterResultBuilder<M extends ApiMessage>
            extends FilterResultBuilder<ResponseFilterResultBuilder<M>, ResponseFilterResult<M>, M, ResponseHeaderData> {
        @Override
        public ResponseFilterResult<M> build() {
            var builderThis = this;
            return new ResponseFilterResult<M>() {
                @Override
                public ResponseHeaderData header() {
                    return builderThis.header();
                }

                @Override
                public M message() {
                    return builderThis.message();
                }

                @Override
                public boolean closeConnection() {
                    return builderThis.closeConnection();
                }
            };
        }
    }

    public static class ShortCircuitResponseFilterResultBuilder<M extends ApiMessage> extends ResponseFilterResultBuilder {

        private final RequestFilterResultBuilder<M> requestFilterResultBuilder;

        private ShortCircuitResponseFilterResultBuilder(RequestFilterResultBuilder<M> requestFilterResultBuilder) {
            this.requestFilterResultBuilder = requestFilterResultBuilder;
        }

        public RequestFilterResultBuilder<M> end() {
            return requestFilterResultBuilder;
        }
    }

}