#!/bin/bash
# Quick single test run for issue #3745
# Runs all 5 parameterized test cases

TEST="io.kroxylicious.it.PluginTlsApiIT#clientTlsContextMutualTls"

echo "Running: $TEST (all parameterized cases)"
mvn test -P-qa -Derrorprone.skip=true \
    -pl kroxylicious-integration-tests \
    -Dtest="$TEST"
