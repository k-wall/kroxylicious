/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.internal.VirtualClusterRegistry;
import io.kroxylicious.proxy.internal.config.Features;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for race condition between startup() and shutdown() calls.
 *
 * This test demonstrates a bug where if shutdown() is called while the proxy
 * is in STARTING state (after doStartup() has completed initialization but
 * before the final transition to STARTED), the startup thread will throw
 * LifecycleException even though the proxy is properly shutting down.
 */
class KafkaProxyStartupShutdownRaceTest {

    private static final String DEMO1_CONFIG = """
            network:
              proxy:
                shutdownQuietPeriod: 0s
              management:
                shutdownQuietPeriod: 0s
            virtualClusters:
              - name: demo1
                targetCluster:
                  bootstrapServers: kafka.example:1234
                gateways:
                - name: default
                  portIdentifiesNode:
                    bootstrapAddress: localhost:9192
            """;

    private ConfigParser configParser;
    private KafkaProxy proxy;

    @BeforeEach
    void setUp() {
        configParser = new ConfigParser();
    }

    @AfterEach
    void tearDown() {
        if (this.proxy != null) {
            this.proxy.close();
        }
    }

    /**
     * This test attempts to trigger a race condition where shutdown() is called
     * while startup() is in STARTING state, after initialization completes but
     * before the transition to STARTED.
     *
     * Expected behavior: shutdown() should gracefully stop the proxy without
     * the startup() call throwing an exception.
     *
     * Actual behavior (BUG): startup() throws LifecycleException with message
     * "failed to start Kroxylicious lifecycle state" even though the proxy
     * shuts down cleanly.
     *
     * This test uses a custom VirtualClusterRegistry to inject a delay at a
     * precise point during startup, creating a window for the race condition.
     */
    @Test
    void shutdownDuringStartupShouldNotCauseStartupException() throws Exception {
        var config = DEMO1_CONFIG;
        var configuration = configParser.parseConfiguration(config);
        var models = configuration.virtualClusterModel(configParser);

        var initializationCompleted = new CountDownLatch(1);
        var allowStartupToComplete = new CountDownLatch(1);

        // Custom registry that signals when initialization is done, then blocks
        // briefly to create a window for the race condition
        var registryWithDelay = new VirtualClusterRegistry(models, (name, cause) -> {
        }) {
            @Override
            public void initializationSucceeded(String clusterName) {
                super.initializationSucceeded(clusterName);
                initializationCompleted.countDown();
                try {
                    // Block here to create a window where we're after initialization
                    // but before the final transition to STARTED
                    allowStartupToComplete.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        this.proxy = new KafkaProxy(configParser, configuration, Features.defaultFeatures(), registryWithDelay);

        var startupException = new AtomicReference<Throwable>();

        // Start the proxy in a separate thread
        var startupThread = new Thread(() -> {
            try {
                proxy.startup().join();
            }
            catch (Throwable e) {
                startupException.set(e);
            }
        });

        startupThread.start();

        // Wait for initialization to complete (we're now in the race window)
        assertThat(initializationCompleted.await(5, TimeUnit.SECONDS))
                .as("Initialization should complete")
                .isTrue();

        // At this point, the startup thread is blocked in initializationSucceeded(),
        // which is called AFTER all initialization is done but BEFORE the transition to STARTED.
        // The proxy is in STARTING state.

        // Call shutdown from this thread - this should transition STARTING -> STOPPING
        proxy.shutdown();

        // Now allow the startup thread to continue
        allowStartupToComplete.countDown();

        // Wait for startup thread to complete
        startupThread.join(5000);

        // BUG: The startup thread will try to transition to STARTED, fail (because we're now STOPPING),
        // and throw LifecycleException even though shutdown() is handling cleanup properly.
        // Additionally, the error handling in doStartup() tries to call initializationFailed()
        // on virtual clusters that shutdown() has already stopped, causing IllegalStateException.

        // This assertion demonstrates the bug exists
        assertThat(startupException.get())
                .as("BUG: startup() should not throw when shutdown() is called during startup")
                .isNotNull()
                .satisfies(ex -> {
                    // The actual exception thrown is IllegalStateException from trying to fail
                    // already-stopped virtual clusters
                    assertThat(ex).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Cannot initializationFailed")
                            .hasMessageContaining("in state Stopped");
                });
    }

    /**
     * Alternative test using reflection to observe state transitions.
     * This test is more timing-dependent but doesn't require custom registry.
     */
    @Test
    void concurrentShutdownDuringStartupStateTransition() throws Exception {
        var config = DEMO1_CONFIG;
        this.proxy = new KafkaProxy(configParser, configParser.parseConfiguration(config), Features.defaultFeatures());

        // Use reflection to access the private state field
        Field stateField = KafkaProxy.class.getDeclaredField("state");
        stateField.setAccessible(true);

        var startupException = new AtomicReference<Throwable>();
        var startupComplete = new CountDownLatch(1);

        var startupThread = new Thread(() -> {
            try {
                proxy.startup().join();
            }
            catch (Throwable e) {
                startupException.set(e);
            }
            finally {
                startupComplete.countDown();
            }
        });

        startupThread.start();

        // Busy-wait until we observe STARTING state
        var observed = false;
        for (int i = 0; i < 1000; i++) {
            @SuppressWarnings("unchecked")
            var state = ((AtomicReference<Object>) stateField.get(proxy)).get();
            if (state.toString().equals("STARTING")) {
                observed = true;
                // Give doStartup() time to progress toward completion
                // but not enough time to transition to STARTED
                Thread.sleep(100);
                break;
            }
            Thread.sleep(1);
        }

        assertThat(observed)
                .as("Should observe STARTING state")
                .isTrue();

        // Call shutdown while (hopefully) still in STARTING state
        proxy.shutdown();

        // Wait for startup thread
        assertThat(startupComplete.await(5, TimeUnit.SECONDS))
                .as("Startup thread should complete")
                .isTrue();

        // If we hit the race window, startup() may have thrown
        // Note: This test is timing-dependent and may not always trigger the bug
        if (startupException.get() != null) {
            assertThat(startupException.get())
                    .as("BUG: Demonstrates race condition where startup() throws during concurrent shutdown")
                    .satisfies(ex -> {
                        // May throw either LifecycleException or IllegalStateException
                        // depending on exact timing
                        assertThat(ex).isInstanceOfAny(LifecycleException.class, IllegalStateException.class);
                    });
        }
    }
}
