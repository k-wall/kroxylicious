#!/bin/bash
# More aggressive reproduction of issue #3745
# Uses even more stress and runs all parameterized test cases

set -e

TEST_CLASS="io.kroxylicious.it.PluginTlsApiIT"
TEST_METHOD="clientTlsContextMutualTls"
MAX_ITERATIONS=${1:-100}

PASS_COUNT=0
FAIL_COUNT=0
ITERATION=0

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$STRESS_PID" ] && kill -0 "$STRESS_PID" 2>/dev/null; then
        echo "Stopping stress (PID: $STRESS_PID)"
        kill "$STRESS_PID" 2>/dev/null || true
        wait "$STRESS_PID" 2>/dev/null || true
    fi
    echo ""
    echo "=== Results ==="
    echo "Total iterations: $ITERATION"
    echo "Passes: $PASS_COUNT"
    echo "Failures: $FAIL_COUNT"
    if [ $FAIL_COUNT -gt 0 ]; then
        echo ""
        echo "✓ SUCCESS: Reproduced the issue!"
        exit 0
    else
        echo ""
        echo "✗ No failures in $ITERATION iterations"
        exit 1
    fi
}

trap cleanup EXIT INT TERM

# More aggressive stress - max out the system
CPU_COUNT=$(sysctl -n hw.ncpu)
echo "=== Starting AGGRESSIVE stress load ==="
echo "CPUs available: $CPU_COUNT"
echo "Command: stress --cpu $CPU_COUNT --io $CPU_COUNT --vm $CPU_COUNT --vm-bytes 256M --timeout 1200s"
stress --cpu "$CPU_COUNT" --io "$CPU_COUNT" --vm "$CPU_COUNT" --vm-bytes 256M --timeout 1200s &
STRESS_PID=$!
echo "Stress started with PID: $STRESS_PID"
echo ""

sleep 3

echo "=== Running test iterations ==="
echo "Test: ${TEST_CLASS}#${TEST_METHOD} (ALL parameterized cases)"
echo "Max iterations: $MAX_ITERATIONS"
echo ""

while [ $ITERATION -lt $MAX_ITERATIONS ]; do
    ITERATION=$((ITERATION + 1))
    printf "[%3d/%3d] Running test... " "$ITERATION" "$MAX_ITERATIONS"

    # Run ALL parameterized test cases (not just [3])
    if mvn test -P-qa -Derrorprone.skip=true \
        -pl kroxylicious-integration-tests \
        -Dtest="${TEST_CLASS}#${TEST_METHOD}" \
        -DfailIfNoTests=false \
        > /tmp/aggressive-test-run-$ITERATION.log 2>&1; then
        echo "PASS"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "FAIL ✗"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        echo ""
        echo "=== Failure detected on iteration $ITERATION ==="
        echo "Log saved to: /tmp/aggressive-test-run-$ITERATION.log"
        echo ""
        echo "Failure excerpt:"
        echo "----------------------------------------"
        grep -A 30 "FAILURE" /tmp/aggressive-test-run-$ITERATION.log | head -50
        echo "----------------------------------------"
        break
    fi

    if [ $ITERATION -gt 10 ]; then
        rm -f /tmp/aggressive-test-run-$((ITERATION - 10)).log 2>/dev/null || true
    fi
done
