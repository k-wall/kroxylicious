/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.reconciler.kafkaprotocolrouter;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolRouter;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolRouterBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolRouterStatus;
import io.kroxylicious.kubernetes.operator.Annotations;
import io.kroxylicious.kubernetes.operator.ResourceState;
import io.kroxylicious.kubernetes.operator.ResourcesUtil;
import io.kroxylicious.kubernetes.operator.StatusFactory;
import io.kroxylicious.kubernetes.operator.checksum.MetadataChecksumGenerator;

/**
 * Factory for building status patches for {@link KafkaProtocolRouter} resources.
 */
public class KafkaProtocolRouterStatusFactory extends StatusFactory<KafkaProtocolRouter> {

    /**
     * Constructs a status factory for KafkaProtocolRouter resources.
     *
     * @param clock the clock used for condition timestamps
     */
    public KafkaProtocolRouterStatusFactory(Clock clock) {
        super(clock);
    }

    private KafkaProtocolRouter routerStatusPatch(KafkaProtocolRouter observedRouter,
                                                  Condition condition, String checksum) {
        // @formatter:off
        var metadataBuilder = new KafkaProtocolRouterBuilder()
                .withNewMetadata()
                    .withUid(ResourcesUtil.uid(observedRouter))
                    .withName(ResourcesUtil.name(observedRouter))
                    .withNamespace(ResourcesUtil.namespace(observedRouter));
        if (!checksum.isBlank()) {
            Annotations.annotateWithReferentChecksum(metadataBuilder, checksum);
        }
        return metadataBuilder
                .endMetadata()
                .withNewStatus()
                    .withObservedGeneration(ResourcesUtil.generation(observedRouter))
                    .withConditions(ResourceState.newConditions(Optional.ofNullable(observedRouter.getStatus()).map(KafkaProtocolRouterStatus::getConditions).orElse(List.of()), ResourceState.of(condition)))
                .endStatus()
                .build();
        // @formatter:on
    }

    @Override
    public KafkaProtocolRouter newUnknownConditionStatusPatch(KafkaProtocolRouter observedRouter,
                                                              Condition.Type type,
                                                              Exception e) {
        Condition unknownCondition = newUnknownCondition(observedRouter, type, e);
        return routerStatusPatch(observedRouter, unknownCondition, MetadataChecksumGenerator.NO_CHECKSUM_SPECIFIED);
    }

    @Override
    public KafkaProtocolRouter newFalseConditionStatusPatch(KafkaProtocolRouter observedRouter,
                                                            Condition.Type type,
                                                            String reason,
                                                            String message) {
        Condition falseCondition = newFalseCondition(observedRouter, type, reason, message);
        return routerStatusPatch(observedRouter, falseCondition, MetadataChecksumGenerator.NO_CHECKSUM_SPECIFIED);
    }

    @Override
    public KafkaProtocolRouter newTrueConditionStatusPatch(KafkaProtocolRouter observedRouter,
                                                           Condition.Type type, String checksum) {
        Condition trueCondition = newTrueCondition(observedRouter, type);
        return routerStatusPatch(observedRouter, trueCondition, checksum);
    }
}
