/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filters;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.CreateTopicsRequestData;
import org.apache.kafka.common.message.CreateTopicsResponseData;
import org.apache.kafka.common.message.DeleteTopicsRequestData;
import org.apache.kafka.common.message.DeleteTopicsResponseData;
import org.apache.kafka.common.message.DescribeGroupsRequestData;
import org.apache.kafka.common.message.DescribeGroupsResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;

import io.kroxylicious.proxy.filter.CreateTopicsRequestFilter;
import io.kroxylicious.proxy.filter.CreateTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsRequestFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DescribeGroupsRequestFilter;
import io.kroxylicious.proxy.filter.DescribeGroupsResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.ProduceRequestFilter;
import io.kroxylicious.proxy.filter.ProduceResponseFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;

public class EightInterfaceFilter implements ProduceResponseFilter, ProduceRequestFilter, CreateTopicsRequestFilter, CreateTopicsResponseFilter,
        DeleteTopicsRequestFilter, DeleteTopicsResponseFilter, DescribeGroupsRequestFilter, DescribeGroupsResponseFilter {

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

    @Override
    public CompletionStage<RequestFilterResult<CreateTopicsRequestData>> onCreateTopicsRequest(short apiVersion, RequestHeaderData header,
                                                                                               CreateTopicsRequestData request,
                                                                                               KrpcFilterContext<CreateTopicsRequestData, CreateTopicsResponseData> context) {

        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<CreateTopicsResponseData>> onCreateTopicsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  CreateTopicsResponseData response,
                                                                                                  KrpcFilterContext<CreateTopicsRequestData, CreateTopicsResponseData> context) {

        return null;
    }

    @Override
    public CompletionStage<RequestFilterResult<DeleteTopicsRequestData>> onDeleteTopicsRequest(short apiVersion, RequestHeaderData header,
                                                                                               DeleteTopicsRequestData request,
                                                                                               KrpcFilterContext<DeleteTopicsRequestData, DeleteTopicsResponseData> context) {

        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<DeleteTopicsResponseData>> onDeleteTopicsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  DeleteTopicsResponseData response,
                                                                                                  KrpcFilterContext<DeleteTopicsRequestData, DeleteTopicsResponseData> context) {

        return null;
    }

    @Override
    public CompletionStage<RequestFilterResult<DescribeGroupsRequestData>> onDescribeGroupsRequest(short apiVersion, RequestHeaderData header,
                                                                                                   DescribeGroupsRequestData request,
                                                                                                   KrpcFilterContext<DescribeGroupsRequestData, DescribeGroupsResponseData> context) {

        return null;
    }

    @Override
    public CompletionStage<ResponseFilterResult<DescribeGroupsResponseData>> onDescribeGroupsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                      DescribeGroupsResponseData response,
                                                                                                      KrpcFilterContext<DescribeGroupsRequestData, DescribeGroupsResponseData> context) {

        return null;
    }
}
