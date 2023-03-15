/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.multitenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MultiTenantTransformationFilterTest {

    @Test
    public void testFindClasspathResource() {
        assertNotNull(MultiTenantTransformationFilterTest.class.getResource("/io/kroxylicious/proxy/filter/multitenant/Fetch.test.yaml"));
    }
}
