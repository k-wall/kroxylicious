/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kroxylicious.doctools.asciidoc;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

public class BlockExtractor implements AutoCloseable {

    private  Asciidoctor asciidoctor;

    public BlockExtractor() {
//        asciidoctor = Asciidoctor.Factory.create();
//        asciidoctor.javaConverterRegistry().register(PassthroughConverter.class, "passthrough");
    }

    public List<Block> extract(Path asciiDocFile, final Predicate<StructuralNode> blockPredicate) {
        List<Block> blocks = new ArrayList<>();
        try (var asciidoctor = Asciidoctor.Factory.create()) {
            asciidoctor.javaConverterRegistry().register(AdocConverter.class);
            asciidoctor.javaExtensionRegistry().treeprocessor(new Treeprocessor() {
                @Override
                public Document process(Document document) {
                    document.getBlocks().stream()
                            .filter(blockPredicate)
                            .forEach(sn -> processBlock(sn, blocks::add));
                    return document;
                }
            });

            Path tempDirectory;
            try {
                tempDirectory = Files.createTempDirectory(asciiDocFile.getFileName().toString());
                try {
                    var options = Options.builder()
                            .option(Options.SOURCEMAP, "true") // require so source file/line number information is available
                            .option(Options.TO_DIR, tempDirectory.toString()) // don't want the output
                            .safe(SafeMode.UNSAFE) // Required to write the output to temp location
                            .backend("adoc")
                            .build();

                    System.out.println("blocking " + asciiDocFile);
                    asciidoctor.convertFile(asciiDocFile.toFile(), options, String.class);
                }
                finally {
                    deleteAll(tempDirectory);
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            finally {
                System.out.println("Saw " + blocks.size() + " from " + asciiDocFile);

            }
        }
        return blocks;
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

    private void processBlock(StructuralNode structuralNode, Consumer<Block> blockConsumer) {
        var content = String.valueOf(structuralNode.getContent());
        // https://github.com/asciidoctor/asciidoctor/issues/1061
        content = demungeEntities(content);
        var sourceLocation = structuralNode.getSourceLocation();
        var block = new Block(new File(sourceLocation.getFile()).toPath(), sourceLocation.getLineNumber(), demungeEntities(content));
        blockConsumer.accept(block);

    }

    private static String demungeEntities(String content) {
        return  content.replace("&lt;", "<")
                .replace("&gt;", ">");
    }


    @ConverterFor(value = "passthrough", suffix = ".txt")
    public static class PassthroughConverter extends StringConverter {

        private final String LINE_SEPARATOR = "\n";

        public PassthroughConverter(String backend, Map<String, Object> opts) {
            super(backend, opts);
        }

        @Override
        public String convert(ContentNode node, String transform, Map<Object, Object> o) {

            if (node instanceof StructuralNode block) {
                System.out.println("converting " + block.getSourceLocation().getFile() + ":" + block.getSourceLocation().getLineNumber());
            }


            if (transform == null) {
                transform = node.getNodeName();
            }

            if (node instanceof Document document) {
                System.out.println("Document  " + node.getNodeName() );
                return (String) document.getContent();
            }
            else if (node instanceof Section section) {
                System.out.println("Section  " + node.getNodeName() );
                return new StringBuilder()
                        .append("== ").append(section.getTitle()).append(" ==")
                        .append(LINE_SEPARATOR).append(LINE_SEPARATOR)
                        .append(section.getContent()).toString();
            }
            else if (transform.equals("paragraph")) {
                System.out.println("paragraph  " + node.getNodeName() + " clazz " + node.getClass().getName());

                StructuralNode block = (StructuralNode) node;
                String content = (String) block.getContent();
                return new StringBuilder(content.replaceAll(LINE_SEPARATOR, " ")).append(LINE_SEPARATOR).toString();
            }
            else if (node instanceof org.asciidoctor.ast.List list) {
                System.out.println("org.asciidoctor.ast.List  " + node.getNodeName() );
                StringBuilder sb = new StringBuilder();
                for (StructuralNode listItem: list.getItems()) {
                    sb.append(listItem.convert()).append(LINE_SEPARATOR);
                }
                return sb.toString();
            }
            else if (node instanceof org.asciidoctor.ast.DescriptionList list) {
                System.out.println("org.asciidoctor.ast.DescriptionList  " + node.getNodeName() );
                StringBuilder sb = new StringBuilder();
                for (var listItem: list.getItems()) {
                    sb.append(listItem.getDescription().convert()).append(LINE_SEPARATOR);
                }
                return sb.toString();
            }
            else if (node instanceof ListItem listItem) {
                System.out.println("ListItem  " + node.getNodeName() );
                return "-> " + listItem.getText();
            }
            else if (node instanceof StructuralNode block) {
                System.out.println("StructuralNode " + node.getNodeName() + " content is " + Optional.of(node.getClass()).map(Class::getName).getClass());
                System.out.println("processing " + block.getSourceLocation().getFile() + ":" + block.getSourceLocation().getLineNumber());
                return Optional.ofNullable(block.getContent()).map(String::valueOf).orElse(null);
            }
            else {
                System.out.println("Other");
            }
            return null;
        }
    }

    @Override
    public void close() {
       // asciidoctor.close();
    }
}
