/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

public class RequestForwardDelayingFilter implements RequestFilter {

    @Override
    public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        CompletableFuture<RequestFilterResult> future = new CompletableFuture<>();
        var builder = filterContext.requestFilterResultBuilder();
        try (var executor = Executors.newScheduledThreadPool(1)) {
            var delay = (long) (Math.random() * 200);
            executor.schedule(() -> {
                future.complete(builder.forward(header, body).build());
            }, delay, TimeUnit.MILLISECONDS);
        }
        return future;
    }
}