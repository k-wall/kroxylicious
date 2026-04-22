# Reproducing Issue #3745

## Issue Summary

Non-deterministic test failure in `PluginTlsApiIT.clientTlsContextMutualTls[3]`.

**Root Cause**: Race condition where Kafka messages can enter the filter chain before the Transport Subject (containing TLS client certificate info) is built.

**Original CI Failure**: Test index `[3]` which uses `MyTransportSubjectBuilderService.Config(0, false)` - immediate failure in subject building.

## Reproduction Methods

### Method 1: Automated Script (Recommended)

The script runs the test repeatedly under CPU/IO/memory pressure using the `stress` tool:

```bash
# Run all parameterized test cases (indices [0] through [4])
./reproduce-3745.sh

# Custom iteration count
./reproduce-3745.sh 100
```

**Note:** Maven's `-Dtest` filter doesn't support JUnit parameterized test index notation like `[3]`, so the script runs all 5 parameterized cases. The race can occur in any of them when the subject builder is involved.

**What it does:**
- Starts `stress` tool creating CPU/IO/VM pressure (16 workers each)
- Runs the test up to N times (default: 50)
- Stops on first failure
- Logs saved to `/tmp/test-run-*.log`

**Requirements:**
- `stress` tool installed (`brew install stress` on macOS)

### Method 2: Manual Reproduction

Run the test manually while stress is running in another terminal:

**Terminal 1 - Start stress:**
```bash
stress --cpu 16 --io 16 --vm 16 --vm-bytes 128M --timeout 600s
```

**Terminal 2 - Run test in a loop:**
```bash
# All test cases
for i in {1..50}; do
  echo "Iteration $i"
  mvn test -P-qa -Derrorprone.skip=true \
    -pl kroxylicious-integration-tests \
    -Dtest="io.kroxylicious.it.PluginTlsApiIT#clientTlsContextMutualTls" \
    || break
done

# Or target specific index [3]
for i in {1..50}; do
  echo "Iteration $i"
  mvn test -P-qa -Derrorprone.skip=true \
    -pl kroxylicious-integration-tests \
    -Dtest="io.kroxylicious.it.PluginTlsApiIT#clientTlsContextMutualTls[3]" \
    || break
done
```

### Method 3: Single Test Run (for debugging)

Run a single test execution with debugging enabled:

```bash
mvn test -P-qa -Derrorprone.skip=true \
  -pl kroxylicious-integration-tests \
  -Dtest="io.kroxylicious.it.PluginTlsApiIT#clientTlsContextMutualTls[3]"
```

## Understanding the Test Parameters

The test is parameterized with 5 configurations:

| Index | Config | Description |
|-------|--------|-------------|
| [0] | `null` | No custom subject builder (default behavior) |
| [1] | `Config(0, true)` | Immediate successful completion |
| [2] | `Config(100, true)` | 100ms delay, then successful completion |
| **[3]** | **`Config(0, false)`** | **Immediate failure (throws exception)** ← CI failure |
| [4] | `Config(100, false)` | 100ms delay, then failure |

## Expected Behavior

When the race condition occurs:
- **Expected**: Subject contains client certificate principals: `Subject[principals=[User[name=CN=client, ...]]]`
- **Actual**: Subject is empty: `Subject[principals=[]]`

This happens when the filter sees the message before the Transport Subject build completes (or fails).

## The Fix (PR #3761)

The fix introduces a `KafkaProxyGatewayHandler` that:
1. Buffers messages until the Transport Subject is ready
2. Ensures the SSL handshake completes before messages enter the filter chain
3. Coordinates with `ProxyChannelStateMachine` to control message flow

## Related Issues

- #3748 - Discovered during investigation, already fixed separately
- PR #3761 - The proposed fix for this race condition

## Debugging Tips

If you want to add extra logging (as Rob did):

1. Check out Rob's debugging branch: `https://github.com/robobario/kroxylicious/tree/3745-debugging`
2. Review the additional logging in:
   - `KafkaProxyFrontendHandler`
   - `ProxyChannelStateMachine`
   - Transport subject builders
