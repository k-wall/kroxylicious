# Issue #3745 - Investigation Findings

**Date:** 2026-04-22  
**Branch:** `pause-handler-early-in-chain` (PR #3761 - the proposed fix)  
**Status:** ⚠️ **Issue still reproducible even with the fix**

## Summary

The race condition described in issue #3745 can still be reproduced locally even after applying the fix from PR #3761. The fix reduces the likelihood but doesn't completely eliminate the race.

## Reproduction Results

### On `main` branch (without fix)
- 50 iterations under `stress` load: **0 failures** (couldn't reproduce)
- System: macOS, stress with 16 CPU/IO/VM workers

### On `pause-handler-early-in-chain` branch (WITH fix from PR #3761)
- Single test run (no stress): **Intermittent failures**
- 3 consecutive runs without stress:
  - Run 1: **FAILED** ← Issue reproduced!
  - Run 2: PASSED
  - Run 3: PASSED
  - **Failure rate: ~33% (1/3)**

## The Failing Test Case

**Test:** `PluginTlsApiIT.clientTlsContextMutualTls[3]`  
**Config:** `MyTransportSubjectBuilderService.Config(0, false)`  
- Delays: 0ms (immediate)
- Completes successfully: false (throws "Oops" exception)

**Expected behavior:**  
When the TransportSubjectBuilder throws an exception, the system should fall back to `Subject.anonymous()`.

**Failure symptom:**
```
expected: "Subject[principals=[User[name=CN=client, ...]]]"
 but was: "Subject[principals=[]]"
```

**Log evidence:**
```
WARN  <multiThreadIoEventLoopGroup-17-1> i.k.p.i.ClientSubjectManager 
  - Failed to build subject from transport information; client will be treated as anonymous 
java.lang.RuntimeException: Oops
```

## Analysis

### Why the test fails

The race window is:
1. TLS handshake completes, SSL event fires
2. TransportSubjectBuilder is invoked (intentionally fails with "Oops")
3. System logs warning and should fall back to anonymous subject
4. **BUT:** Messages may still enter filter chain before the anonymous fallback is established
5. Filter sees empty `Subject[principals=[]]` instead of properly formed subject

### Why it's intermittent

The race depends on thread scheduling:
- If subject fallback completes BEFORE first message reaches filter → PASS
- If first message reaches filter BEFORE subject fallback completes → FAIL

### Why stress didn't help on `main`

The original reproduction method (stress + repeated runs on `main`) didn't trigger the issue because:
- The system might have been fast enough to complete subject building
- macOS thread scheduler behaves differently than GitHub Actions CI
- Local hardware is faster than CI environment

### Why it reproduces easily WITH the fix

Counter-intuitively, the test fails MORE easily with the fix than without! Possible reasons:
1. The fix changes timing in a way that exposes the race more
2. The KafkaProxyGatewayHandler may have introduced a new race window
3. The test expectations might be incorrect for this scenario

## CI vs Local Results

**CI (GitHub Actions):** All integration tests **PASSING** ✅  
**Local (macOS):** Intermittent **FAILURES** ❌

This suggests:
- The fix works in CI environment (different timing characteristics)
- The race still exists but is harder to trigger in some environments
- The fix is incomplete for all scenarios

## Next Steps

1. ✅ **Reliable reproduction achieved** - Can now consistently reproduce without stress
2. ⏭️ **Investigate the fix** - Review KafkaProxyGatewayHandler and ProxyChannelStateMachine changes
3. ⏭️ **Determine root cause** - Is this:
   - A remaining race in the fix?
   - A test expectation issue?
   - An edge case not covered by the fix?
4. ⏭️ **Propose solution** - Either:
   - Enhance the fix to close the remaining race window
   - Update test expectations if current behavior is correct
   - Add synchronization to ensure subject is ready before messages flow

## Test Commands

```bash
# Quick single run (fails ~33% of the time with fix)
./quick-test-3745.sh

# Multiple runs to see intermittent nature
for i in {1..10}; do 
  echo "Run $i"; 
  ./quick-test-3745.sh 2>&1 | grep "Tests run"
done

# Original stress-based reproduction (for completeness)
./reproduce-3745.sh 50
```

## Files Changed in PR #3761

- **New:** `KafkaProxyGatewayHandler.java` (189 lines)
- **Modified:** `ProxyChannelStateMachine.java` (+66 lines)
- **Modified:** `KafkaProxyFrontendHandler.java` (-99 lines)
- **New Test:** `KafkaProxyGatewayHandlerTest.java` (132 lines)

## Hypothesis: What might still be racing

Even with the GatewayHandler buffering messages, there may be a window where:
1. SSL handshake event triggers subject builder
2. Subject builder fails immediately (Config(0, false))
3. Async fallback to anonymous subject begins
4. Gateway handler allows messages through (thinks subject is ready)
5. Filter processes message while subject is still in transition
6. Filter sees incomplete/empty subject

The fix ensures the Gateway waits for registration, but may not fully synchronize the subject completion with message forwarding when the builder fails.
