/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class FilterResultBuilderTest {

    @Test
    public void requestFilterResult() {
        var request = new FetchRequestData();
        var builder = FilterResultBuilder.requestFilterResultBuilder();
        builder.withMessage(request);
        RequestFilterResult built = builder.build();
        assertThat(built.message()).isEqualTo(request);
    }

    @Test
    public void requestFilterResultRejectResponse() {
        var response = new FetchResponseData();
        var builder = FilterResultBuilder.requestFilterResultBuilder();
        assertThrows(IllegalStateException.class, () -> builder.withMessage(response));
    }

    @Test
    public void requestFilterShortCircuitResult() {
        var builder = FilterResultBuilder.requestFilterResultBuilder().withShortCircuitResponse();
        builder.withMessage(new FetchResponseData());
        builder.build();
    }

}
