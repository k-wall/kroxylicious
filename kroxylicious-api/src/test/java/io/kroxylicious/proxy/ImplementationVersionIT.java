/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parent POM configures the maven-jar-plugin to add default implementation entries
 * ({@code addDefaultImplementationEntries}), so every packaged jar carries the project version
 * as {@code Implementation-Version}. These integration tests run after the jar is packaged
 * and assert on the real manifest.
 */
class ImplementationVersionIT {

    /** Set by the failsafe configuration in kroxylicious-api/pom.xml. */
    private static final String EXPECTED_VERSION = System.getProperty("implementation.version");

    private Path packagedJar() {
        return Path.of("target", "kroxylicious-api-" + EXPECTED_VERSION + ".jar");
    }

    @Test
    void packagedJarManifestCarriesImplementationEntries() throws Exception {
        try (var jarFile = new JarFile(packagedJar().toFile())) {
            Attributes manifest = jarFile.getManifest().getMainAttributes();
            assertThat(manifest.getValue(Attributes.Name.IMPLEMENTATION_VERSION)).isEqualTo(EXPECTED_VERSION);
            assertThat(manifest.getValue(Attributes.Name.IMPLEMENTATION_TITLE)).isEqualTo("Kroxylicious API");
            assertThat(manifest.getValue(Attributes.Name.IMPLEMENTATION_VENDOR)).isEqualTo("Kroxylicious");
        }
    }

    @Test
    void implementationVersionIsReadableFromClassLoadedFromJar() throws Exception {
        var jarUrl = packagedJar().toUri().toURL();
        try (var loader = new URLClassLoader(new URL[]{ jarUrl }, getClass().getClassLoader())) {
            var filterClass = loader.loadClass("io.kroxylicious.proxy.filter.Filter");
            assertThat(filterClass.getPackage().getImplementationVersion()).isEqualTo(EXPECTED_VERSION);
        }
    }

    @Test
    void testClasspathContextCarriesNoImplementationVersion() {
        // Surefire runs tests against target/classes, which has no jar manifest, so a
        // Class.getPackage().getImplementationVersion() read there must yield null.
        assertThat(getClass().getPackage().getImplementationVersion()).isNull();
    }

}