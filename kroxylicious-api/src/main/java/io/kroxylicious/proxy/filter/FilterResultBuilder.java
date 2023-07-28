/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;

public abstract class FilterResultBuilder<M, H> {
    private ApiMessage message;
    private H header;
    private boolean closeConnection;

    private FilterResultBuilder() {
    }

    public FilterResultBuilder<M, H> withHeader(H header) {
        this.header = header;
        return this;
    }

    H header() {
        return header;
    }

    public FilterResultBuilder<M, H> withMessage(ApiMessage message) {
        this.message = message;
        return this;
    }

    ApiMessage message() {
        return message;
    }

    public FilterResultBuilder<M, H> withCloseConnection(boolean closeConnection) {
        this.closeConnection = closeConnection;
        return this;
    }

    boolean closeConnection() {
        return closeConnection;
    }

    public abstract M build();

    public static ResponseFilterResultBuilder responseFilterResultBuilder() {
        return new ResponseFilterResultBuilder();
    }

    public static RequestFilterResultBuilder requestFilterResultBuilder() {
        return new RequestFilterResultBuilder();
    }

    public static class RequestFilterResultBuilder extends FilterResultBuilder<RequestFilterResult, RequestHeaderData> {

        private ShortCircuitResponseFilterResultBuilder shortCircuitResponseBuilder;

        public ShortCircuitResponseFilterResultBuilder withShortCircuitResponse() {
            if (shortCircuitResponseBuilder == null) {
                shortCircuitResponseBuilder = new ShortCircuitResponseFilterResultBuilder(this);
            }
            return shortCircuitResponseBuilder;
        }

        @Override
        public RequestFilterResult build() {

            // TODO: API really needs to prevent a user configuring a forward request and a short-circuit response.
            var builder = this;
            var shortCircuitResponse = shortCircuitResponseBuilder == null ? null : shortCircuitResponseBuilder.build();

            return new RequestFilterResult() {
                @Override
                public RequestHeaderData header() {
                    if (shortCircuitResponse != null) {
                        throw new IllegalStateException();
                    }
                    return builder.header();
                }

                @Override
                public ApiMessage message() {
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

                public ResponseFilterResult shortCircuitResponse() {
                    return shortCircuitResponse;
                }
            };
        }
    }

    public static class ResponseFilterResultBuilder extends FilterResultBuilder<ResponseFilterResult, ResponseHeaderData> {
        @Override
        public ResponseFilterResult build() {
            var builderThis = this;
            return new ResponseFilterResult() {
                @Override
                public ResponseHeaderData header() {
                    return builderThis.header();
                }

                @Override
                public ApiMessage message() {
                    return builderThis.message();
                }

                @Override
                public boolean closeConnection() {
                    return builderThis.closeConnection();
                }
            };
        }
    }

    public static class ShortCircuitResponseFilterResultBuilder extends ResponseFilterResultBuilder {

        private final RequestFilterResultBuilder requestFilterResultBuilder;

        private ShortCircuitResponseFilterResultBuilder(RequestFilterResultBuilder requestFilterResultBuilder) {
            this.requestFilterResultBuilder = requestFilterResultBuilder;
        }

        public RequestFilterResultBuilder end() {
            return requestFilterResultBuilder;
        }
    }

}