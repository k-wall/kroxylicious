/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import io.kroxylicious.proxy.config.VirtualCluster;

public record VirtualClusterBinding(VirtualCluster virtualCluster, Integer nodeId) {
}
