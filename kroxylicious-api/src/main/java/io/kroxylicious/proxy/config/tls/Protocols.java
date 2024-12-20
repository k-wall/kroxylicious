/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

public enum Protocols {
    SSL("SSL"),
    SSLv2("SSLv2"),
    SSLv3("SSLv3"),
    TLS("TLS"),
    TLSv1_1("TLSv1.1"),
    TLSv1_2("TLSv1.2"),
    TLSv1_3("TLSv1.3");

    String sslProtocol;

    Protocols(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }
}
