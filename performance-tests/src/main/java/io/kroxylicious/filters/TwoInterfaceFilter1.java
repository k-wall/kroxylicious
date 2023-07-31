/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filters;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;

import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.ProduceRequestFilter;
import io.kroxylicious.proxy.filter.ProduceResponseFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;

public class TwoInterfaceFilter1 implements ProduceResponseFilter, ProduceRequestFilter {

    @Override
    public CompletionStage<RequestFilterResult<ProduceRequestData>> onProduceRequest(short apiVersion, RequestHeaderData header, ProduceRequestData request,
                                                                                     KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<ProduceResponseData>> onProduceResponse(short apiVersion, ResponseHeaderData header, ProduceResponseData response,
                                                                                        KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        return null;
    }
}
