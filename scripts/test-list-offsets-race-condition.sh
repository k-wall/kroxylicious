#!/bin/bash
# Script to reproduce the race condition in ListOffsetsAuthzIT
# Related to issue #3767
#
# This script runs the test repeatedly under CPU/IO stress to trigger
# the race condition where Node.noNode() is treated as a valid leader.
#
# Usage: ./test-list-offsets-race-condition.sh [num_iterations]
#

set -e

ITERATIONS=${1:-30}
STRESS_TIMEOUT=180

echo "========================================="
echo "ListOffsetsAuthzIT Race Condition Test"
echo "========================================="
echo ""
echo "This test reproduces the race condition reported in #3767"
echo "by running single sequential test instances under CPU/IO stress."
echo ""
echo "Iterations: $ITERATIONS"
echo "Stress timeout: ${STRESS_TIMEOUT}s"
echo ""

# Check if stress is available
if ! command -v stress &> /dev/null; then
    echo "ERROR: 'stress' utility not found. Please install it:"
    echo "  macOS: brew install stress"
    echo "  Linux: apt-get install stress / yum install stress"
    exit 1
fi

# Change to integration tests directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT/kroxylicious-integration-tests" || exit 1

echo "Starting stress (CPU+IO)..."
stress --cpu 2 --io 1 --timeout ${STRESS_TIMEOUT}s &
STRESS_PID=$!
echo "Stress PID: $STRESS_PID"
sleep 2

echo ""
echo "Running $ITERATIONS sequential test iterations..."
echo ""

race_found=0
pass_count=0
fail_count=0

for i in $(seq 1 $ITERATIONS); do
  printf "Run %2d/%d: " $i $ITERATIONS

  # Run the test
  result=$(mvn test -Dtest=ListOffsetsAuthzIT#shouldEnforceAccessToTopics -P-qa -Derrorprone.skip=true -q 2>&1)

  # Check for the specific race condition (errorCode mismatches)
  if echo "$result" | grep -q "Different value found.*errorCode"; then
    echo "🔴 RACE CONDITION DETECTED!"
    echo ""
    echo "=== Race Condition Details ==="
    echo "$result" | grep -B 3 -A 20 "Different value found" | head -30
    echo ""
    race_found=1
    break
  elif echo "$result" | grep -q "Failures: 0, Errors: 0"; then
    echo "✓ pass"
    ((pass_count++))
  else
    echo "✗ other failure"
    ((fail_count++))
  fi

  # Brief pause for cleanup
  sleep 0.5
done

# Stop stress
kill $STRESS_PID 2>/dev/null || true
wait $STRESS_PID 2>/dev/null || true

echo ""
echo "========================================="
echo "Results:"
echo "  Passed: $pass_count/$ITERATIONS"
echo "  Failed (other): $fail_count/$ITERATIONS"
echo "  Race conditions detected: $race_found"
echo "========================================="
echo ""

if [ $race_found -eq 1 ]; then
  echo "❌ Race condition reproduced!"
  echo "   This indicates the Node.noNode() bug is present."
  exit 1
else
  echo "✅ No race conditions detected"
  echo "   The fix appears to be working correctly."
  exit 0
fi
