/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonCreator;

public record FilePasswordSource(String passwordFile) implements PasswordSource {
    @JsonCreator
    public FilePasswordSource {
    }

    @Override
    public String getPasswordAsCharArray() {
        if (passwordFile == null) {
            return null;
        }
        try (var fr = new BufferedReader(new FileReader(passwordFile, StandardCharsets.UTF_8))) {
            return fr.readLine();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "FilePasswordSource[" +
                "passwordFile=" + passwordFile + ']';
    }

}
