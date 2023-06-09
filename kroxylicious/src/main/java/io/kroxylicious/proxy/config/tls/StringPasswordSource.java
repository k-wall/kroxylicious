/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import com.fasterxml.jackson.annotation.JsonCreator;

public record StringPasswordSource(String password) implements PasswordSource {
    @JsonCreator
    public StringPasswordSource {
    }

    @Override
    public String getPasswordAsCharArray() {
        return password == null ? null : password;
    }

}
