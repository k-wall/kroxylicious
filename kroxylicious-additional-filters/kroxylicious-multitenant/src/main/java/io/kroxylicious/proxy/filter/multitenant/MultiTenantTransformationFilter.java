/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter.multitenant;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.kafka.common.message.AddOffsetsToTxnRequestData;
import org.apache.kafka.common.message.AddOffsetsToTxnResponseData;
import org.apache.kafka.common.message.AddPartitionsToTxnRequestData;
import org.apache.kafka.common.message.AddPartitionsToTxnResponseData;
import org.apache.kafka.common.message.CreateTopicsRequestData;
import org.apache.kafka.common.message.CreateTopicsResponseData;
import org.apache.kafka.common.message.DeleteTopicsRequestData;
import org.apache.kafka.common.message.DeleteTopicsResponseData;
import org.apache.kafka.common.message.DescribeGroupsRequestData;
import org.apache.kafka.common.message.DescribeGroupsResponseData;
import org.apache.kafka.common.message.DescribeTransactionsRequestData;
import org.apache.kafka.common.message.DescribeTransactionsResponseData;
import org.apache.kafka.common.message.EndTxnRequestData;
import org.apache.kafka.common.message.EndTxnResponseData;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.HeartbeatRequestData;
import org.apache.kafka.common.message.HeartbeatResponseData;
import org.apache.kafka.common.message.InitProducerIdRequestData;
import org.apache.kafka.common.message.InitProducerIdResponseData;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.LeaveGroupRequestData;
import org.apache.kafka.common.message.LeaveGroupResponseData;
import org.apache.kafka.common.message.ListGroupsRequestData;
import org.apache.kafka.common.message.ListGroupsResponseData;
import org.apache.kafka.common.message.ListOffsetsRequestData;
import org.apache.kafka.common.message.ListOffsetsResponseData;
import org.apache.kafka.common.message.ListTransactionsRequestData;
import org.apache.kafka.common.message.ListTransactionsResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.message.OffsetCommitResponseData;
import org.apache.kafka.common.message.OffsetDeleteRequestData;
import org.apache.kafka.common.message.OffsetDeleteResponseData;
import org.apache.kafka.common.message.OffsetFetchRequestData;
import org.apache.kafka.common.message.OffsetFetchResponseData;
import org.apache.kafka.common.message.OffsetForLeaderEpochRequestData;
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.message.TxnOffsetCommitRequestData;
import org.apache.kafka.common.message.TxnOffsetCommitResponseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.filter.AddOffsetsToTxnRequestFilter;
import io.kroxylicious.proxy.filter.AddPartitionsToTxnRequestFilter;
import io.kroxylicious.proxy.filter.AddPartitionsToTxnResponseFilter;
import io.kroxylicious.proxy.filter.CreateTopicsRequestFilter;
import io.kroxylicious.proxy.filter.CreateTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsRequestFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DescribeGroupsRequestFilter;
import io.kroxylicious.proxy.filter.DescribeGroupsResponseFilter;
import io.kroxylicious.proxy.filter.DescribeTransactionsRequestFilter;
import io.kroxylicious.proxy.filter.DescribeTransactionsResponseFilter;
import io.kroxylicious.proxy.filter.EndTxnRequestFilter;
import io.kroxylicious.proxy.filter.FetchRequestFilter;
import io.kroxylicious.proxy.filter.FetchResponseFilter;
import io.kroxylicious.proxy.filter.FindCoordinatorRequestFilter;
import io.kroxylicious.proxy.filter.FindCoordinatorResponseFilter;
import io.kroxylicious.proxy.filter.HeartbeatRequestFilter;
import io.kroxylicious.proxy.filter.InitProducerIdRequestFilter;
import io.kroxylicious.proxy.filter.JoinGroupRequestFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.LeaveGroupRequestFilter;
import io.kroxylicious.proxy.filter.ListGroupsResponseFilter;
import io.kroxylicious.proxy.filter.ListOffsetsRequestFilter;
import io.kroxylicious.proxy.filter.ListOffsetsResponseFilter;
import io.kroxylicious.proxy.filter.ListTransactionsResponseFilter;
import io.kroxylicious.proxy.filter.MetadataRequestFilter;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;
import io.kroxylicious.proxy.filter.OffsetCommitRequestFilter;
import io.kroxylicious.proxy.filter.OffsetCommitResponseFilter;
import io.kroxylicious.proxy.filter.OffsetDeleteRequestFilter;
import io.kroxylicious.proxy.filter.OffsetDeleteResponseFilter;
import io.kroxylicious.proxy.filter.OffsetFetchRequestFilter;
import io.kroxylicious.proxy.filter.OffsetFetchResponseFilter;
import io.kroxylicious.proxy.filter.OffsetForLeaderEpochRequestFilter;
import io.kroxylicious.proxy.filter.OffsetForLeaderEpochResponseFilter;
import io.kroxylicious.proxy.filter.ProduceRequestFilter;
import io.kroxylicious.proxy.filter.ProduceResponseFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.filter.SyncGroupRequestFilter;
import io.kroxylicious.proxy.filter.TxnOffsetCommitRequestFilter;
import io.kroxylicious.proxy.filter.TxnOffsetCommitResponseFilter;

/**
 * Simple multi-tenant filter.
 * <br/>
 * Uses the first component of a fully-qualified host name as a tenant prefix.
 * This tenant prefix is prepended to the kafka resources name in order to present an isolated
 * environment for each tenant.
 * <br/>
 * TODO disallow the use of topic uids belonging to one tenant by another.
 */
public class MultiTenantTransformationFilter
        implements CreateTopicsRequestFilter, CreateTopicsResponseFilter,
        DeleteTopicsRequestFilter, DeleteTopicsResponseFilter,
        MetadataRequestFilter, MetadataResponseFilter,
        ProduceRequestFilter, ProduceResponseFilter,
        ListOffsetsRequestFilter, ListOffsetsResponseFilter,
        FetchRequestFilter, FetchResponseFilter,
        OffsetFetchRequestFilter, OffsetFetchResponseFilter,
        OffsetCommitRequestFilter, OffsetCommitResponseFilter,
        OffsetDeleteRequestFilter, OffsetDeleteResponseFilter,
        OffsetForLeaderEpochRequestFilter, OffsetForLeaderEpochResponseFilter,
        FindCoordinatorRequestFilter, FindCoordinatorResponseFilter,
        ListGroupsResponseFilter,
        JoinGroupRequestFilter,
        SyncGroupRequestFilter,
        LeaveGroupRequestFilter,
        HeartbeatRequestFilter,
        DescribeGroupsRequestFilter, DescribeGroupsResponseFilter,
        InitProducerIdRequestFilter,
        ListTransactionsResponseFilter,
        DescribeTransactionsRequestFilter, DescribeTransactionsResponseFilter,
        EndTxnRequestFilter,
        AddPartitionsToTxnRequestFilter, AddPartitionsToTxnResponseFilter,
        AddOffsetsToTxnRequestFilter,
        TxnOffsetCommitRequestFilter, TxnOffsetCommitResponseFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiTenantTransformationFilter.class);

    @Override
    public CompletionStage<RequestFilterResult<CreateTopicsRequestData>> onCreateTopicsRequest(short apiVersion, RequestHeaderData header,
                                                                                               CreateTopicsRequestData request,
                                                                                               KrpcFilterContext<CreateTopicsRequestData, CreateTopicsResponseData> context) {
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<CreateTopicsResponseData>> onCreateTopicsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  CreateTopicsResponseData response,
                                                                                                  KrpcFilterContext<CreateTopicsRequestData, CreateTopicsResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<DeleteTopicsRequestData>> onDeleteTopicsRequest(short apiVersion, RequestHeaderData header,
                                                                                               DeleteTopicsRequestData request,
                                                                                               KrpcFilterContext<DeleteTopicsRequestData, DeleteTopicsResponseData> context) {
        // the topicName field was present up to and including version 5
        request.setTopicNames(request.topicNames().stream().map(topic -> applyTenantPrefix(context, topic)).toList());
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, topic.topicId() != null));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<DeleteTopicsResponseData>> onDeleteTopicsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  DeleteTopicsResponseData response,
                                                                                                  KrpcFilterContext<DeleteTopicsRequestData, DeleteTopicsResponseData> context) {
        response.responses().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<MetadataRequestData>> onMetadataRequest(short apiVersion, RequestHeaderData header, MetadataRequestData request,
                                                                                       KrpcFilterContext<MetadataRequestData, MetadataResponseData> context) {
        if (request.topics() != null) {
            // n.b. request.topics() == null used to query all the topics.
            request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        }
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<MetadataResponseData>> onMetadataResponse(short apiVersion, ResponseHeaderData header, MetadataResponseData response,
                                                                                          KrpcFilterContext<MetadataRequestData, MetadataResponseData> context) {
        String tenantPrefix = getTenantPrefix(context);
        response.topics().removeIf(topic -> !topic.name().startsWith(tenantPrefix)); // TODO: allow kafka internal topics to be returned?
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<ProduceRequestData>> onProduceRequest(short apiVersion, RequestHeaderData header, ProduceRequestData request,
                                                                                     KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        applyTenantPrefix(context, request::transactionalId, request::setTransactionalId, true);
        request.topicData().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<ProduceResponseData>> onProduceResponse(short apiVersion, ResponseHeaderData header, ProduceResponseData response,
                                                                                        KrpcFilterContext<ProduceRequestData, ProduceResponseData> context) {
        response.responses().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<ListOffsetsRequestData>> onListOffsetsRequest(short apiVersion, RequestHeaderData header, ListOffsetsRequestData request,
                                                                                             KrpcFilterContext<ListOffsetsRequestData, ListOffsetsResponseData> context) {
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<ListOffsetsResponseData>> onListOffsetsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                ListOffsetsResponseData response,
                                                                                                KrpcFilterContext<ListOffsetsRequestData, ListOffsetsResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<OffsetFetchRequestData>> onOffsetFetchRequest(short apiVersion, RequestHeaderData header, OffsetFetchRequestData request,
                                                                                             KrpcFilterContext<OffsetFetchRequestData, OffsetFetchResponseData> context) {
        // the groupId and top-level topic fields were present up to and including version 7
        Optional.ofNullable(request.groupId()).ifPresent(groupId -> applyTenantPrefix(context, request::groupId, request::setGroupId, true));
        Optional.ofNullable(request.topics()).ifPresent(topics -> topics.forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false)));
        request.groups().forEach(requestGroup -> {
            applyTenantPrefix(context, requestGroup::groupId, requestGroup::setGroupId, false);
            Optional.ofNullable(requestGroup.topics())
                    .ifPresent(topics -> topics.forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false)));
        });

        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<OffsetFetchResponseData>> onOffsetFetchResponse(short apiVersion, ResponseHeaderData header,
                                                                                                OffsetFetchResponseData response,
                                                                                                KrpcFilterContext<OffsetFetchRequestData, OffsetFetchResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        response.groups().forEach(responseGroup -> {
            removeTenantPrefix(context, responseGroup::groupId, responseGroup::setGroupId, false);
            responseGroup.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        });
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<OffsetForLeaderEpochRequestData>> onOffsetForLeaderEpochRequest(short apiVersion, RequestHeaderData header,
                                                                                                               OffsetForLeaderEpochRequestData request,
                                                                                                               KrpcFilterContext<OffsetForLeaderEpochRequestData, OffsetForLeaderEpochResponseData> context) {
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::topic, topic::setTopic, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<OffsetForLeaderEpochResponseData>> onOffsetForLeaderEpochResponse(short apiVersion, ResponseHeaderData header,
                                                                                                                  OffsetForLeaderEpochResponseData response,
                                                                                                                  KrpcFilterContext<OffsetForLeaderEpochRequestData, OffsetForLeaderEpochResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::topic, topic::setTopic, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<OffsetCommitRequestData>> onOffsetCommitRequest(short apiVersion, RequestHeaderData header,
                                                                                               OffsetCommitRequestData request,
                                                                                               KrpcFilterContext<OffsetCommitRequestData, OffsetCommitResponseData> context) {
        applyTenantPrefix(context, request::groupId, request::setGroupId, false);
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<OffsetCommitResponseData>> onOffsetCommitResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  OffsetCommitResponseData response,
                                                                                                  KrpcFilterContext<OffsetCommitRequestData, OffsetCommitResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<OffsetDeleteRequestData>> onOffsetDeleteRequest(short apiVersion, RequestHeaderData header,
                                                                                               OffsetDeleteRequestData request,
                                                                                               KrpcFilterContext<OffsetDeleteRequestData, OffsetDeleteResponseData> context) {
        applyTenantPrefix(context, request::groupId, request::setGroupId, false);
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<OffsetDeleteResponseData>> onOffsetDeleteResponse(short apiVersion, ResponseHeaderData header,
                                                                                                  OffsetDeleteResponseData response,
                                                                                                  KrpcFilterContext<OffsetDeleteRequestData, OffsetDeleteResponseData> context) {
        response.topics().forEach(topic -> removeTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<FetchRequestData>> onFetchRequest(short apiVersion, RequestHeaderData header, FetchRequestData request,
                                                                                 KrpcFilterContext<FetchRequestData, FetchResponseData> context) {
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::topic, topic::setTopic, topic.topicId() != null));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<FetchResponseData>> onFetchResponse(short apiVersion, ResponseHeaderData header, FetchResponseData response,
                                                                                    KrpcFilterContext<FetchRequestData, FetchResponseData> context) {
        response.responses().forEach(topic -> removeTenantPrefix(context, topic::topic, topic::setTopic, topic.topicId() != null));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<FindCoordinatorRequestData>> onFindCoordinatorRequest(short apiVersion, RequestHeaderData header,
                                                                                                     FindCoordinatorRequestData request,
                                                                                                     KrpcFilterContext<FindCoordinatorRequestData, FindCoordinatorResponseData> context) {
        // the key fields was present up to and including version 4
        Optional.ofNullable(request.key()).ifPresent(unused -> applyTenantPrefix(context, request::key, request::setKey, true));
        request.setCoordinatorKeys(request.coordinatorKeys().stream().map(key -> applyTenantPrefix(context, key)).toList());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<FindCoordinatorResponseData>> onFindCoordinatorResponse(short apiVersion, ResponseHeaderData header,
                                                                                                        FindCoordinatorResponseData response,
                                                                                                        KrpcFilterContext<FindCoordinatorRequestData, FindCoordinatorResponseData> context) {
        response.coordinators().forEach(coordinator -> removeTenantPrefix(context, coordinator::key, coordinator::setKey, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<ResponseFilterResult<ListGroupsResponseData>> onListGroupsResponse(short apiVersion, ResponseHeaderData header,
                                                                                              ListGroupsResponseData response,
                                                                                              KrpcFilterContext<ListGroupsRequestData, ListGroupsResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        var filteredGroups = response.groups().stream().filter(listedGroup -> listedGroup.groupId().startsWith(tenantPrefix)).toList();
        filteredGroups.forEach(listedGroup -> removeTenantPrefix(context, listedGroup::groupId, listedGroup::setGroupId, false));
        response.setGroups(filteredGroups);
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<JoinGroupRequestData>> onJoinGroupRequest(short apiVersion, RequestHeaderData header, JoinGroupRequestData request,
                                                                                         KrpcFilterContext<JoinGroupRequestData, JoinGroupResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setGroupId(tenantPrefix + request.groupId());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<SyncGroupRequestData>> onSyncGroupRequest(short apiVersion, RequestHeaderData header, SyncGroupRequestData request,
                                                                                         KrpcFilterContext<SyncGroupRequestData, SyncGroupResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setGroupId(tenantPrefix + request.groupId());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<LeaveGroupRequestData>> onLeaveGroupRequest(short apiVersion, RequestHeaderData header, LeaveGroupRequestData request,
                                                                                           KrpcFilterContext<LeaveGroupRequestData, LeaveGroupResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setGroupId(tenantPrefix + request.groupId());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<HeartbeatRequestData>> onHeartbeatRequest(short apiVersion, RequestHeaderData header, HeartbeatRequestData request,
                                                                                         KrpcFilterContext<HeartbeatRequestData, HeartbeatResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setGroupId(tenantPrefix + request.groupId());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<DescribeGroupsRequestData>> onDescribeGroupsRequest(short apiVersion, RequestHeaderData header,
                                                                                                   DescribeGroupsRequestData request,
                                                                                                   KrpcFilterContext<DescribeGroupsRequestData, DescribeGroupsResponseData> context) {
        request.setGroups(request.groups().stream().map(group -> applyTenantPrefix(context, group)).toList());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<DescribeGroupsResponseData>> onDescribeGroupsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                      DescribeGroupsResponseData response,
                                                                                                      KrpcFilterContext<DescribeGroupsRequestData, DescribeGroupsResponseData> context) {
        response.groups().forEach(group -> removeTenantPrefix(context, group::groupId, group::setGroupId, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<InitProducerIdRequestData>> onInitProducerIdRequest(short apiVersion, RequestHeaderData header,
                                                                                                   InitProducerIdRequestData request,
                                                                                                   KrpcFilterContext<InitProducerIdRequestData, InitProducerIdResponseData> context) {
        applyTenantPrefix(context, request::transactionalId, request::setTransactionalId, true);
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<AddPartitionsToTxnRequestData>> onAddPartitionsToTxnRequest(short apiVersion, RequestHeaderData header,
                                                                                                           AddPartitionsToTxnRequestData request,
                                                                                                           KrpcFilterContext<AddPartitionsToTxnRequestData, AddPartitionsToTxnResponseData> context) {
        request.v3AndBelowTopics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        applyTenantPrefix(context, request::v3AndBelowTransactionalId, request::setV3AndBelowTransactionalId, true);

        request.transactions().forEach(addPartitionsToTxnTransaction -> {
            applyTenantPrefix(context, addPartitionsToTxnTransaction::transactionalId, addPartitionsToTxnTransaction::setTransactionalId, true);
            addPartitionsToTxnTransaction.topics().forEach(addPartitionsToTxnTopic -> {
                applyTenantPrefix(context, addPartitionsToTxnTopic::name, addPartitionsToTxnTopic::setName, true);
            });
        });
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<AddPartitionsToTxnResponseData>> onAddPartitionsToTxnResponse(short apiVersion, ResponseHeaderData header,
                                                                                                              AddPartitionsToTxnResponseData response,
                                                                                                              KrpcFilterContext<AddPartitionsToTxnRequestData, AddPartitionsToTxnResponseData> context) {
        response.resultsByTopicV3AndBelow().forEach(results -> removeTenantPrefix(context, results::name, results::setName, false));

        response.resultsByTransaction().forEach(addPartitionsToTxnResult -> {
            removeTenantPrefix(context, addPartitionsToTxnResult::transactionalId, addPartitionsToTxnResult::setTransactionalId, false);
            for (AddPartitionsToTxnResponseData.AddPartitionsToTxnTopicResult topicResult : addPartitionsToTxnResult.topicResults()) {
                removeTenantPrefix(context, topicResult::name, topicResult::setName, true);
            }
        });
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<AddOffsetsToTxnRequestData>> onAddOffsetsToTxnRequest(short apiVersion, RequestHeaderData header,
                                                                                                     AddOffsetsToTxnRequestData request,
                                                                                                     KrpcFilterContext<AddOffsetsToTxnRequestData, AddOffsetsToTxnResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setTransactionalId(tenantPrefix + request.transactionalId());
        request.setGroupId(tenantPrefix + request.groupId());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<RequestFilterResult<TxnOffsetCommitRequestData>> onTxnOffsetCommitRequest(short apiVersion, RequestHeaderData header,
                                                                                                     TxnOffsetCommitRequestData request,
                                                                                                     KrpcFilterContext<TxnOffsetCommitRequestData, TxnOffsetCommitResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setTransactionalId(tenantPrefix + request.transactionalId());
        request.setGroupId(tenantPrefix + request.groupId());
        request.topics().forEach(topic -> applyTenantPrefix(context, topic::name, topic::setName, false));
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<TxnOffsetCommitResponseData>> onTxnOffsetCommitResponse(short apiVersion, ResponseHeaderData header,
                                                                                                        TxnOffsetCommitResponseData response,
                                                                                                        KrpcFilterContext<TxnOffsetCommitRequestData, TxnOffsetCommitResponseData> context) {
        response.topics().forEach(results -> removeTenantPrefix(context, results::name, results::setName, false));
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<ResponseFilterResult<ListTransactionsResponseData>> onListTransactionsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                          ListTransactionsResponseData response,
                                                                                                          KrpcFilterContext<ListTransactionsRequestData, ListTransactionsResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        var filteredTransactions = response.transactionStates().stream().filter(listedTxn -> listedTxn.transactionalId().startsWith(tenantPrefix)).toList();
        filteredTransactions.forEach(listedTxn -> removeTenantPrefix(context, listedTxn::transactionalId, listedTxn::setTransactionalId, false));
        response.setTransactionStates(filteredTransactions);
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<DescribeTransactionsRequestData>> onDescribeTransactionsRequest(short apiVersion, RequestHeaderData header,
                                                                                                               DescribeTransactionsRequestData request,
                                                                                                               KrpcFilterContext<DescribeTransactionsRequestData, DescribeTransactionsResponseData> context) {
        request.setTransactionalIds(request.transactionalIds().stream().map(group -> applyTenantPrefix(context, group)).toList());
        return context.completedForwardRequest(request);
    }

    @Override
    public CompletionStage<ResponseFilterResult<DescribeTransactionsResponseData>> onDescribeTransactionsResponse(short apiVersion, ResponseHeaderData header,
                                                                                                                  DescribeTransactionsResponseData response,
                                                                                                                  KrpcFilterContext<DescribeTransactionsRequestData, DescribeTransactionsResponseData> context) {
        response.transactionStates().forEach(ts -> {
            removeTenantPrefix(context, ts::transactionalId, ts::setTransactionalId, false);
            ts.topics().forEach(t -> removeTenantPrefix(context, t::topic, t::setTopic, false));
        });
        return context.completedForwardResponse(response);
    }

    @Override
    public CompletionStage<RequestFilterResult<EndTxnRequestData>> onEndTxnRequest(short apiVersion, RequestHeaderData header, EndTxnRequestData request,
                                                                                   KrpcFilterContext<EndTxnRequestData, EndTxnResponseData> context) {
        var tenantPrefix = getTenantPrefix(context);
        request.setTransactionalId(tenantPrefix + request.transactionalId());
        return context.completedForwardRequest(request);
    }

    private void applyTenantPrefix(KrpcFilterContext<?, ?> context, Supplier<String> getter, Consumer<String> setter, boolean ignoreEmpty) {
        String clientSideName = getter.get();
        if (ignoreEmpty && (clientSideName == null || clientSideName.isEmpty())) {
            return;
        }
        setter.accept(applyTenantPrefix(context, clientSideName));
    }

    private String applyTenantPrefix(KrpcFilterContext<?, ?> context, String clientSideName) {
        var tenantPrefix = getTenantPrefix(context);
        return tenantPrefix + clientSideName;
    }

    private void removeTenantPrefix(KrpcFilterContext<?, ?> context, Supplier<String> getter, Consumer<String> setter, boolean ignoreEmpty) {
        var brokerSideName = getter.get();
        if (ignoreEmpty && (brokerSideName == null || brokerSideName.isEmpty())) {
            return;
        }

        setter.accept(removeTenantPrefix(context, brokerSideName));
    }

    private String removeTenantPrefix(KrpcFilterContext<?, ?> context, String brokerSideName) {
        var tenantPrefix = getTenantPrefix(context);
        return brokerSideName.substring(tenantPrefix.length());
    }

    private static String getTenantPrefix(KrpcFilterContext<?, ?> context) {
        // TODO naive - POC implementation uses the first component of a FQDN as the multi-tenant prefix.
        var sniHostname = context.sniHostname();
        if (sniHostname == null) {
            throw new IllegalStateException("This filter requires that the client provides a TLS SNI hostname.");
        }
        int dot = sniHostname.indexOf(".");
        if (dot < 1) {
            throw new IllegalStateException("Unexpected SNI hostname formation. SNI hostname : " + sniHostname);
        }
        return sniHostname.substring(0, dot) + "-";
    }

    public MultiTenantTransformationFilter() {
    }
}
