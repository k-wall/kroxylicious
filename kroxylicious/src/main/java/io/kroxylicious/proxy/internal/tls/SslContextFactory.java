/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.tls;

import io.netty.handler.ssl.SslContext;

import io.kroxylicious.proxy.config.tls.Tls;

public interface SslContextFactory {

    SslContext buildServerSslContext(Tls tls);

    SslContext buildClientSslContext(Tls tls);
}
