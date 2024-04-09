/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.crypto;

import java.nio.ByteBuffer;

import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.ByteUtils;

public class AadNone implements Aad {

    @Override
    public ByteBuffer computeAad(
                                 String topicName,
                                 int partitionId,
                                 RecordBatch batch) {
        return ByteUtils.EMPTY_BUF;
    }
}
