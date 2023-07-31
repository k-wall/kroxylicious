/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filters;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.openjdk.jmh.infra.Blackhole;

import io.kroxylicious.proxy.filter.ApiVersionsRequestFilter;
import io.kroxylicious.proxy.filter.ApiVersionsResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.ProduceRequestFilter;
import io.kroxylicious.proxy.filter.ProduceResponseFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;

import static io.kroxylicious.benchmarks.InvokerDispatchBenchmark.CONSUME_TOKENS;

public class FourInterfaceFilter1 implements ProduceResponseFilter, ProduceRequestFilter, ApiVersionsRequestFilter, ApiVersionsResponseFilter {
    @Override
    public CompletionStage<RequestFilterResult<ProduceRequestData>> onProduceRequest(short apiVersion, RequestHeaderData header, ProduceRequestData request,
                                                                                     KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        Blackhole.consumeCPU(CONSUME_TOKENS);
        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<ProduceResponseData>> onProduceResponse(short apiVersion, ResponseHeaderData header, ProduceResponseData response,
                                                                                        KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        Blackhole.consumeCPU(CONSUME_TOKENS);
        return null;
    }

    @Override
    public CompletionStage<RequestFilterResult<ApiVersionsRequestData>> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                                             KrpcFilterContext<ApiVersionsRequestData, ApiVersionsResponseData> context) {
        Blackhole.consumeCPU(CONSUME_TOKENS);
        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<ApiVersionsResponseData>> onApiVersionsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                ApiVersionsResponseData response,
                                                                                                KrpcFilterContext<ApiVersionsRequestData, ApiVersionsResponseData> context) {
        Blackhole.consumeCPU(CONSUME_TOKENS);
        return null;
    }
}
