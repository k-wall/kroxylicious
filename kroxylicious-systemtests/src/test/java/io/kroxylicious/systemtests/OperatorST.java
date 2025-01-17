/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;

import static org.assertj.core.api.Assertions.assertThat;

public class OperatorST {


    @BeforeEach
    void beforeEach() throws Exception {

        // install CRDs
        var config = new ConfigBuilder().build();
        try (KubernetesClient kubernetesClient = new KubernetesClientBuilder().withConfig(config).build()) {

            var operatorRoot = findOperatorRoot();


            var crdRoot = operatorRoot.resolve("src/main/resources/META-INF/fabric8");
            try (var crds = Files.walk(crdRoot)
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .filter(f -> f.getName().endsWith(".yml"))) {
                crds.forEach(yaml -> {
                            try {
                                var resources = kubernetesClient.load(new FileInputStream(yaml));
                                System.out.println("applying " + resources.resources().toList().size() + " from " + yaml);
                                resources.delete();
                                resources.serverSideApply();
                            }
                            catch (FileNotFoundException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }

            var installRoot = operatorRoot.resolve("install");
            Namespace build = new NamespaceBuilder().withNewMetadata().withName("kroxylicious-operator").endMetadata().build();
            kubernetesClient.namespaces().resource(build).serverSideApply();
            try (var installFiles = Files.walk(installRoot)
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .filter(f -> f.getName().endsWith(".yaml"))) {
                installFiles.forEach(yaml -> {
                    try {
                        var resources = kubernetesClient.load(new FileInputStream(yaml));
                        System.out.println("applying " + resources.resources().toList().size() + " from " + yaml);
                        var operatorDeployment = resources.resources()
                                .map(Resource::item)
                                .filter(Deployment.class::isInstance)
                                .map(Deployment.class::cast)
                                .findFirst();

                        operatorDeployment.ifPresent(d -> {
                            var container = d.getSpec().getTemplate().getSpec().getContainers().get(0);
                            var image = container.getImage();
                            System.out.println("Here's where I could patch the image of the deployment " + image);
                        });
                        resources.serverSideApply();
                    }
                    catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    }


                });
            }

            //            ClassPath.from(this.getClass().getClassLoader()).getResources().stream().forEach(x -> System.out.println(x));

           // kubernetesClient.load()

        }


    }

    private Path findOperatorRoot() throws Exception {
        String clazzFile = this.getClass().getSimpleName() + ".class";
        var resourceAtRoot = Objects.requireNonNull(OperatorST.class.getResource(clazzFile)).toURI().toURL().getFile();
        var current = Path.of(resourceAtRoot);
        String operatorDirName = "kroxylicious-operator";
        while (!current.resolve(operatorDirName).toFile().isDirectory()) {
            current = current.getParent();
        }
        return current.resolve(operatorDirName);
    }

    @Test
    void test() {
        assertThat(true).isTrue();
    }
    @AfterEach
    void afterEach() {


    }

}
