#!/bin/bash
# Script to reproduce issue #3745 - race condition in PluginTlsApiIT.clientTlsContextMutualTls
#
# This script runs the test repeatedly under CPU/IO/VM pressure to trigger
# the race condition where messages enter the filter chain before the Transport Subject
# is built.

set -e

# Configuration
TEST_CLASS="io.kroxylicious.it.PluginTlsApiIT"
TEST_METHOD="clientTlsContextMutualTls"
MAX_ITERATIONS=${1:-50}
STRESS_TIMEOUT=1200s  # 20 minutes total

# Counters
PASS_COUNT=0
FAIL_COUNT=0
ITERATION=0

# Cleanup function
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
        echo "  Check /tmp/test-run-*.log for failure details"
        exit 0
    else
        echo ""
        echo "✗ No failures observed in $ITERATION iterations"
        echo ""
        echo "The race condition may be very rare on this system."
        echo "Try:"
        echo "  - More iterations: ./reproduce-3745.sh 200"
        echo "  - Aggressive stress: ./aggressive-reproduce-3745.sh"
        exit 1
    fi
}

trap cleanup EXIT INT TERM

# Start stress in the background
echo "=== Reproducing Issue #3745 ==="
echo "Test: ${TEST_CLASS}#${TEST_METHOD}"
echo "Iterations: $MAX_ITERATIONS"
echo ""
echo "=== Starting stress load ==="
echo "Command: stress --cpu 16 --io 16 --vm 16 --vm-bytes 128M --timeout $STRESS_TIMEOUT"
stress --cpu 16 --io 16 --vm 16 --vm-bytes 128M --timeout "$STRESS_TIMEOUT" &
STRESS_PID=$!
echo "Stress started with PID: $STRESS_PID"
echo ""

# Give stress a moment to ramp up
sleep 2

echo "=== Running test iterations ==="
echo "Running all 5 parameterized test cases per iteration"
echo "(Maven -Dtest filter doesn't support JUnit index notation)"
echo ""

# Run test iterations
while [ $ITERATION -lt $MAX_ITERATIONS ]; do
    ITERATION=$((ITERATION + 1))
    printf "[%3d/%3d] Running test... " "$ITERATION" "$MAX_ITERATIONS"

    # Run all parameterized test cases
    # Using -P-qa to skip quality checks and -Derrorprone.skip=true to speed up build
    if mvn test -P-qa -Derrorprone.skip=true \
        -pl kroxylicious-integration-tests \
        -Dtest="${TEST_CLASS}#${TEST_METHOD}" \
        -DfailIfNoTests=false \
        > /tmp/test-run-$ITERATION.log 2>&1; then
        echo "PASS (5/5)"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "FAIL ✗"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        echo ""
        echo "=== Failure detected on iteration $ITERATION ==="
        echo "Log saved to: /tmp/test-run-$ITERATION.log"
        echo ""
        echo "Failure excerpt:"
        echo "----------------------------------------"
        # Extract the relevant failure information
        grep -B 5 -A 30 "org.assertj.core.error.AssertJMultipleFailuresError" /tmp/test-run-$ITERATION.log || \
        grep -A 30 "\[ERROR\].*FAILURE" /tmp/test-run-$ITERATION.log | head -40
        echo "----------------------------------------"
        echo ""
        echo "Full log: /tmp/test-run-$ITERATION.log"
        break
    fi

    # Clean up old logs to save space (keep only last 5 and any failures)
    if [ $ITERATION -gt 5 ]; then
        CLEANUP_ITERATION=$((ITERATION - 5))
        rm -f /tmp/test-run-$CLEANUP_ITERATION.log 2>/dev/null || true
    fi
done

# cleanup() will be called automatically via trap
