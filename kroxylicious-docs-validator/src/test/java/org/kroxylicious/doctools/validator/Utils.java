/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kroxylicious.doctools.validator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jruby.RubyProcess;

import static org.assertj.core.api.Assertions.assertThat;

public class Utils {
    static Stream<Path> allAsciiDocFiles() {

        var docs = Path.of("").toAbsolutePath().getParent().resolve("docs");
        assertThat(docs).isDirectory();

        try {
            List<Path> adocs = new ArrayList<>();
            Files.walkFileTree(docs, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().endsWith(".adoc")) {
                        System.out.println(file);
                        adocs.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
            return adocs.stream();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Error walking directory tree", e);
        }
    }
}
