/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kroxylicious.doctools.validator;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kroxylicious.doctools.asciidoc.Block;
import org.kroxylicious.doctools.asciidoc.BlockExtractor;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

public class YamlCodeblockTest {

    private final Yaml yaml = new Yaml();

    public static Stream<Arguments> yamlSourceBlocks() {
        AtomicInteger count = new AtomicInteger();
        try (var extractor = new BlockExtractor()) {
            return Utils.allAsciiDocFiles()
                    .sequential()
                    .peek(x -> System.out.println("visiting file " + count.incrementAndGet() + " " + x))
                    .flatMap(p -> extractor.extract(p).stream())
                    .map(Arguments::of);
        }
        finally {
            System.out.println("Visited " + count.get());
        }
    }

    @ParameterizedTest
    @MethodSource("yamlSourceBlocks")
    void yamlAsciiDocCodeblockSyntacticallyCorrect(Block yamlBlock) throws Exception {

        String content = yamlBlock.content();

        //        assertThatNoException()
        //                .as("Failed to par")
        //                .isThrownBy(() -> yaml.load(content));
    }

    @Test
    void foo() throws Exception {
        var s = Utils.allAsciiDocFiles().toList().size();
        System.out.println("size " + s);
    }
}
