///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.6.3
//DEPS com.fasterxml.jackson.core:jackson-core:2.18.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3
//DEPS org.asciidoctor:asciidoctorj:3.0.0
//DEPS org.asciidoctor:asciidoctorj-api:3.0.0
//DEPS com.google.guava:guava:31.1-jre

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.Document;
import org.asciidoctor.ast.ListItem;
import org.asciidoctor.ast.Section;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.converter.ConverterFor;
import org.asciidoctor.converter.StringConverter;
import org.asciidoctor.extension.Treeprocessor;
import com.google.common.io.CharSource;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "extractAsciidocCodeblocks", mixinStandardHelpOptions = true, version = "extractAsciidocCodeblocks 0.1",
        description = "Extracts codeblocks that match attribute(s) from given Asciidoc source file(s). "
                + "Output is sent yo stdout, or a given command. "
                + "If the command returns a non-zero exit status, processing stops and the filename/line number of the block is reported.")
public class extractAsciiDocCodeblocks implements Callable<Integer> {

    @Parameters(description = "The AsciiDoc source files")
    private List<Path> sourceFiles;

    @Option(names = { "--block-attribute" }, required = false, arity = "1..*", description = "AsciiDoc block attribute(s) to select from the document e.g. --block-attribute language=yaml --block-attribute style=source")
    private Map<String, String> blockAttributes;

    @Option(names = { "--block-command" }, required = false, description = "Name of command to execute.  If command fails, the processing will stop with the name of source line number of of the codeblock written report.  If omitted, codeblock will be written to stdout.")
    private String blockCommand;

    @Option(names = { "--block-arguments" }, required = false, arity = "0..*", description = "Arguments to pass to the block command.")
    private List<String> blockArguments;

    public static void main(String... args) {
        int exitCode = new CommandLine(new extractAsciiDocCodeblocks()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try (var asciidoctor = Asciidoctor.Factory.create()) {
            // a treeparser that rewrites asciidoc input as asciidoc.
            asciidoctor.javaConverterRegistry().register(PassthroughConverter.class, "passthrough");

            asciidoctor.javaExtensionRegistry().treeprocessor(new Treeprocessor() {
                @Override
                public Document process(Document document) {
                    document.getBlocks().stream()
                            .filter(b -> filterBlocks(b))
                            .forEach(b -> processBlock(b)
                            );
                    return document;
                }
            });

            sourceFiles.forEach(sourceFile -> {
                Path tempDirectory = null;
                try {
                    tempDirectory = Files.createTempDirectory(sourceFile.getFileName().toString());
                    try {
                        var options = Options.builder()
                                .option(Options.SOURCEMAP, "true") // Provides source file/line number information
                                .option(Options.TO_DIR, tempDirectory.toString()) // Throw away the output
                                .safe(SafeMode.UNSAFE) // Required to write the output to temp location
                                .backend("passthrough")
                                .build();

                        asciidoctor.convertFile(sourceFile.toFile(), options, String.class);
                    }
                    finally {
                        deleteAll(tempDirectory);
                    }
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        return 0;
    }

    private boolean filterBlocks(StructuralNode block) {
        var actual = block.getAttributes();
        for (Map.Entry<String, String> entry : blockAttributes.entrySet()) {
            var expectedKey = entry.getKey();
            var expectedValue = entry.getValue();
            if (!(actual.containsKey(expectedKey) && expectedValue.equals(actual.get(expectedKey)))) {
                return false;
            }
        }

        return true;
    }

    private void processBlock(StructuralNode b) {
        var content = String.valueOf(b.getContent());
        // https://github.com/asciidoctor/asciidoctor/issues/1061
        content = demungeEntities(content);

        if (blockCommand == null) {
            System.out.println(content);
        }
        else {
            var all = new ArrayList<String>();
            all.add(blockCommand);
            Optional.ofNullable(blockArguments).ifPresent(all::addAll);
            var builder = new ProcessBuilder(all);
            Process p = null;
            try {
                p = builder.start();

                try (var contentStream = asInputStream(new StringReader(content), StandardCharsets.UTF_8);
                        var outputStream = p.getOutputStream()) {
                    contentStream.transferTo(outputStream);
                }
                dumpToPrintStream(p.getInputStream(), System.out);
                dumpToPrintStream(p.getErrorStream(), System.err);
                p.waitFor(1, TimeUnit.MINUTES);

            }
            catch (IOException e) {
                throw new UncheckedIOException("Failed to run command %s for content from %s".formatted(blockCommand, sourceFiles), e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to run command %s for content from %s".formatted(blockCommand, sourceFiles), e);
            }
            finally {
                if (p != null && p.isAlive()) {
                    p.destroy();
                }
            }

            if (p.exitValue() != 0) {
                throw new RuntimeException(
                        "Command %s failed at line %d of file %s whilst processing %s".formatted(blockCommand, b.getSourceLocation().getLineNumber(), b.getSourceLocation().getFile(),
                                this.sourceFiles));
            }

        }
    }

    private void deleteAll(Path tempDirectory) {
        try {
            try (var paths = Files.walk(tempDirectory)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            Files.deleteIfExists(tempDirectory);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String demungeEntities(String content) {
        return  content.replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static void dumpToPrintStream(InputStream inputStream, PrintStream target) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.println(line);
            }
        }
    }

    public InputStream asInputStream(java.io.Reader reader, Charset charset) throws IOException {
        return new CharSource() {
            public java.io.Reader openStream() {
                return reader;
            }
        }.asByteSource(charset).openStream();
    }

    @ConverterFor(value = "passthrough", suffix = ".txt")
    public static class PassthroughConverter extends StringConverter {

        private final String LINE_SEPARATOR = "\n";

        public PassthroughConverter(String backend, Map<String, Object> opts) {
            super(backend, opts);
        }

        @Override
        public String convert(ContentNode node, String transform, Map<Object, Object> o) {
            if (transform == null) {
                transform = node.getNodeName();
            }

            if (node instanceof Document) {
                Document document = (Document) node;
                return (String) document.getContent();
            }
            else if (node instanceof Section) {
                Section section = (Section) node;
                return new StringBuilder()
                        .append("== ").append(section.getTitle()).append(" ==")
                        .append(LINE_SEPARATOR).append(LINE_SEPARATOR)
                        .append(section.getContent()).toString();
            }
            else if (transform.equals("paragraph")) {
                StructuralNode block = (StructuralNode) node;
                String content = (String) block.getContent();
                return new StringBuilder(content.replaceAll(LINE_SEPARATOR, " ")).append(LINE_SEPARATOR).toString();
            }
            else if (node instanceof org.asciidoctor.ast.List) {
                StringBuilder sb = new StringBuilder();
                for (StructuralNode listItem: ((org.asciidoctor.ast.List) node).getItems()) {
                    sb.append(listItem.convert()).append(LINE_SEPARATOR);
                }
                return sb.toString();
            }
            else if (node instanceof ListItem) {
                return "-> " + ((ListItem) node).getText();
            }
            else if (node instanceof StructuralNode) {
                StructuralNode block = (StructuralNode) node;
                return block.getContent().toString();
            }
            return null;
        }
    }
}
