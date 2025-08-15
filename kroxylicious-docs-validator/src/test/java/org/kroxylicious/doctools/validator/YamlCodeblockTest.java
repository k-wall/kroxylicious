/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kroxylicious.doctools.validator;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.asciidoctor.ast.StructuralNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kroxylicious.doctools.asciidoc.Block;
import org.kroxylicious.doctools.asciidoc.BlockExtractor;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThatNoException;

public class YamlCodeblockTest {

    private final Yaml yaml = new Yaml();

    public static Stream<Arguments> yamlSourceBlocks() {
        AtomicInteger count = new AtomicInteger();
        try (var extractor = new BlockExtractor()) {
            return Utils.allAsciiDocFiles()
                    .sequential()
                    .flatMap(p -> extractor.extract(p, sn -> isYamlBlock(sn)).stream())
                    .map(Arguments::of);
        }
        finally {
            System.out.println("Visited " + count.get());
        }
    }

    private static boolean isYamlBlock(StructuralNode sn) {
        return Objects.equals(sn.getAttribute("style", null), "source") &&
                Objects.equals(sn.getAttribute("language", null), "yaml");
    }

    @ParameterizedTest
    @MethodSource("yamlSourceBlocks")
    void yamlAsciiDocCodeblockSyntacticallyCorrect(Block yamlBlock) throws Exception {

        var yaml = yamlBlock.content();
        try {

            this.yaml.load(yaml);
        } catch (Exception e) {
            System.out.println(e);
        }

        assertThatNoException()
                .as("Failed to parse yaml at {} line {}", yamlBlock.asciiDocFile(), yamlBlock.lineNumber())
                .isThrownBy(() -> this.yaml.load(yaml));
    }

}
