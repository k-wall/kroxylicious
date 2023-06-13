/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.io.IOException;
import java.util.Optional;

import javax.net.ssl.SSLException;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import io.kroxylicious.proxy.internal.tls.SslContextFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Requires ability to consume file resources from arbitrary, user-specified, locations on the file-system.")
public class DefaultSslContextFactory implements SslContextFactory {

    @Override
    public SslContext buildServerSslContext(Tls tls) {
        try {
            return Optional.of(tls.key()).map(KeyProvider::forServer).orElseThrow().build();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SslContext buildClientSslContext(Tls clientTls) {

        try {
            var sslContextBuilder = SslContextBuilder.forClient();
            Optional.ofNullable(clientTls.trust()).ifPresent(tp -> tp.apply(sslContextBuilder));
            return sslContextBuilder.build();
        }
        catch (SSLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
