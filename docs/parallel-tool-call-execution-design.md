# Parallel Tool Call Execution System Design

## Status

Draft design based on GitHub Discussion #855, excluding the later one-slot batch-state and event fan-out alternatives.

This document describes a proposed system design for executing multiple tool calls from one `ToolRequestEvent` in parallel while preserving durable execution semantics, mailbox-thread safety, and collect-all tool response behavior.

---

## Motivation

Today, `ToolCallAction` processes tool calls in a single `ToolRequestEvent` serially. Even when async durable execution is enabled, each tool call waits for the previous one to complete before the next tool call starts.

For LLM responses containing multiple independent tool calls, this causes total latency to approximate:

```text
sum(latency_i)
```

The target behavior is:

```text
max(latency_i)
```

by running independent tool calls concurrently.

---

## Goals

1. Execute multiple tool calls from one `ToolRequestEvent` concurrently.
2. Preserve mailbox-thread safety:
   - durable state updates happen on the mailbox thread;
   - memory access happens on the mailbox thread;
   - `sendEvent` happens on the mailbox thread.
3. Preserve collect-all behavior:
   - one tool failure must not fail the whole batch;
   - `ToolResponseEvent` should still contain per-tool success/error/result information.
4. Preserve durable recovery semantics:
   - durable records remain deterministic;
   - completed tool results can be reused after failover;
   - unfinished tool calls can be re-executed or reconciled.
5. Keep final response ordering stable and compatible with existing tool-call ordering.
6. Keep Java and Python semantics aligned.

---

## Non-Goals

1. Changing the external `ToolRequestEvent` / `ToolResponseEvent` wire format.
2. Introducing framework-level automatic retry for every tool.
3. Solving generic fan-out/fan-in workflow orchestration for all action types.
4. Guaranteeing cancellation of blocking external I/O after timeout.
5. Introducing a single composite durable batch slot for all tool calls.
6. Fanning one tool request out into multiple independent internal events/actions.

---

## High-Level Design

The design follows this durable execution shape:

```text
reserve N durable slots
execute N tool calls in parallel
finalize N durable slots in original tool-call order
advance durable cursor by N
emit one ToolResponseEvent
```

Each tool call in the batch maps to one durable call slot:

```text
slot base + 0 -> tool call 0
slot base + 1 -> tool call 1
slot base + 2 -> tool call 2
```

The tool calls execute concurrently, but durable recording remains deterministic by writing terminal results back in the original `tool_calls` order.

---

## Execution Flow

```text
ToolRequestEvent
    |
    v
ToolCallAction
    |
    |-- build deterministic tool callable list in tool_calls order
    |
    |-- scan durable slots [base, base + N)
    |       |-- cached SUCCESS / FAILURE -> reuse
    |       |-- PENDING with reconciler -> reconcile
    |       |-- PENDING without reconciler -> re-execute
    |       |-- missing -> execute
    |
    |-- reserve missing slots as PENDING
    |
    |-- submit executable/reconcilable calls to async executor
    |
    |-- yield until all submitted calls finish or timeout fires
    |
    |-- fan in all outcomes
    |
    |-- finalize slots in index order: base, base + 1, ... base + N - 1
    |
    |-- advance currentCallIndex by N
    |
    |-- emit ToolResponseEvent in original tool_calls order
```

The async execution order is independent. The durable finalization order is deterministic.

---

## Durable Slot Model

Each tool call gets its own durable slot.

Example for three tool calls:

```text
currentCallIndex = base

slot[base + 0] = tool0 durable call
slot[base + 1] = tool1 durable call
slot[base + 2] = tool2 durable call
```

Each slot records:

```java
class CallResult {
    String functionId;
    String argsDigest;
    Status status; // PENDING, SUCCESS, FAILURE
    byte[] resultPayload;
    byte[] exceptionPayload;
}
```

The batch operation is a runtime orchestration over N ordinary durable call slots rather than a new composite durable call type.

---

## Durable Identity

Each tool call should produce a deterministic durable identity.

A conservative v1 can keep the existing function-id strategy and rely on:

```text
functionId + argsDigest + currentCallIndex
```

for recovery matching.

A later refinement may introduce a more specific per-tool function id, for example:

```java
@Override
public String getId() {
    return "tool-call-" + toolCallId;
}
```

However, changing function-id semantics affects recovery compatibility and can be discussed separately. The parallel execution design does not require this change in v1.

---

## Required Durable Primitives

The current durable execution model is cursor-based and serial. Batch execution needs limited index-addressable support over a contiguous range of call slots.

Possible primitives:

```java
List<CallResult> getCallResults(int startIndex, int count);

void reservePendingBatch(
        int startIndex,
        List<String> functionIds,
        List<String> argsDigests);

void finalizeCallAt(
        int callIndex,
        String functionId,
        String argsDigest,
        byte[] resultPayload,
        byte[] exceptionPayload);

void clearCallResultsFrom(int callIndex);

void advanceCallIndexBy(int count);
```

Important properties:

1. `reservePendingBatch` writes a contiguous range of PENDING slots.
2. `finalizeCallAt` writes a terminal result at an absolute durable call index.
3. `advanceCallIndexBy(N)` happens only after all N slots have terminal results.
4. All durable state mutation happens on the mailbox thread.

---

## Fresh Execution

For a fresh batch of N tool calls:

```text
base = currentCallIndex

1. Build callables in original tool_calls order.
2. Reserve PENDING slots [base, base + N).
3. Submit all N tool calls to async executor.
4. Wait/yield until all submitted calls finish or timeout fires.
5. Convert each result/exception/timeout into Outcome<T>.
6. Finalize slots in order:
   finalizeCallAt(base + 0, outcome0)
   finalizeCallAt(base + 1, outcome1)
   ...
   finalizeCallAt(base + N - 1, outcomeN-1)
7. advanceCallIndexBy(N)
8. Emit one ToolResponseEvent.
```

Even if tool 2 completes before tool 0, durable finalization still happens in tool-call order.

---

## Recovery Flow

On recovery, `ToolCallAction` rebuilds the same ordered callable list from the `ToolRequestEvent`.

For each slot in `[base, base + N)`:

| Slot state | Behavior |
| --- | --- |
| Missing | Submit the tool call. |
| Matching SUCCESS | Reuse cached result. |
| Matching FAILURE | Reuse cached failure as a per-tool error. |
| Matching PENDING with reconciler | Run reconciler. |
| Matching PENDING without reconciler | Re-execute the tool call. |
| Mismatch | Clear from mismatch index and re-execute from there. |

Partial recovery example:

```text
slot[base + 0] = SUCCESS
slot[base + 1] = SUCCESS
slot[base + 2] = PENDING
```

Recovery reuses tool 0 and tool 1, and only re-executes or reconciles tool 2.

---

## PENDING Semantics

Existing non-reconcilable single durable calls may not write PENDING records. Batch execution can intentionally reserve PENDING slots for all calls in the batch.

For batch execution:

```text
PENDING + reconciler    -> reconcile
PENDING + no reconciler -> re-execute
```

This makes PENDING a valid batch reservation state. A PENDING slot without a reconciler does not mean a successful null result; it means the tool did not reach a terminal durable state and should be re-run.

---

## Continuation Batch Execution

The RunnerContext-level API is responsible for durable batch planning, recovery matching, batch reservation, ordered finalization, and cursor advancement.

The public or RunnerContext-facing API can be shaped as:

```java
@Override
public <T> List<T> durableExecuteAllAsync(List<DurableCallable<T>> callables) throws Exception {
    Preconditions.checkState(durableExecutionContext != null, "...");
    if (callables.isEmpty()) {
        return List.of();
    }

    String argsDigest = "";
    int base = durableExecutionContext.getCurrentCallIndex();
    int n = callables.size();

    List<Plan<T>> plans = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
        CallResult slot = durableExecutionContext.getCallResultAt(base + i);
        DurableCallable<T> callable = callables.get(i);

        if (slot == null) {
            plans.add(Plan.submit(callable));
        } else if (!slot.matches(callable.getId(), argsDigest)) {
            durableExecutionContext.clearCallResultsFrom(base + i);
            durableExecutionContext.reservePendingBatch(idsFrom(callables, i), argsDigest);
            for (int j = i; j < n; j++) {
                plans.add(Plan.submit(callables.get(j)));
            }
            break;
        } else if (slot.isSuccess() || slot.isFailure()) {
            plans.add(Plan.cached(slot, callable.getResultClass()));
        } else {
            plans.add(callable.reconciler() != null
                    ? Plan.reconcile(callable)
                    : Plan.submit(callable));
        }
    }

    if (isFreshRun(plans, base, n)) {
        durableExecutionContext.reservePendingBatch(allIds(callables), argsDigest);
    }

    List<T> asyncResults =
            continuationExecutor.executeAllAsync(
                    continuationContext,
                    toSuppliers(plans),
                    timeout);

    List<T> results = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
        Outcome<T> outcome = plans.get(i).materialize(asyncResults.get(i));
        durableExecutionContext.finalizeCallAt(
                base + i,
                callables.get(i).getId(),
                argsDigest,
                serializeDurableResult(outcome.result()),
                serializeDurableException(outcome.exception()));
        results.add(outcome.result());
    }

    durableExecutionContext.advanceCallIndexBy(n);
    return results;
}
```

The lower-level continuation executor API is only responsible for async fan-out/fan-in and timeout waiting. It should not expose durable semantics:

```java
public <T> List<T> executeAllAsync(
        ContinuationContext ctx,
        List<Supplier<T>> suppliers,
        Duration timeout)
        throws Exception;
```

For the reserve-N design, durable finalization happens in the RunnerContext fan-in phase, strictly in original tool-call order.

A simple barrier is sufficient for v1 because durable finalization happens after all submitted calls complete or timeout.

```text
submit all executable calls
yield while batch is pending
resume when all submitted calls complete or timeout fires
fan in ordered outcomes
finalize durable slots in order
```

The executor does not need to wake the mailbox for every single tool completion in v1.

A JDK 21 implementation can use continuation yielding:

```java
while (!batchFuture.isDone() && !timedOut()) {
    Continuation.yield(SCOPE);
}
```

The pending batch state should be tracked in `ContinuationContext` so that the action executor knows the action is waiting on async work.

---

## Batch Async State

Example runtime state:

```java
final class BatchAsyncState<T> {
    private final List<Future<Outcome<T>>> futures;
    private final long deadlineNanos;

    boolean isDone() {
        return futures.stream().allMatch(Future::isDone);
    }

    boolean isTimedOut() {
        return System.nanoTime() >= deadlineNanos;
    }

    boolean shouldResume() {
        return isDone() || isTimedOut();
    }
}
```

`ContinuationActionExecutor.executeAction` should treat a pending batch similarly to a pending async call:

```java
if (context.hasPendingBatch() && !context.getPendingBatch().shouldResume()) {
    return false;
}

resumeContinuation();
```

---

## Failure Semantics

Tool-call parallelism should preserve collect-all behavior.

One tool failure should produce a per-tool failure outcome, not fail the entire batch.

```text
tool0 -> success
tool1 -> failure
tool2 -> success

ToolResponseEvent:
  responses: tool0, tool2
  errors:    tool1
  success:   tool0=true, tool1=false, tool2=true
```

The lower-level continuation executor returns a bare `List<T>`. Collect-all failure semantics are handled by the suppliers built by `RunnerContext.durableExecuteAllAsync`: each supplier should catch tool exceptions and return a value that can be materialized into an `Outcome<T>` by its `Plan`.

Recommended RunnerContext-internal shape:

```java
class Outcome<T> {
    boolean success;
    T result;
    Throwable error;
    boolean timeout;
}
```

This avoids ambiguity at the durable fan-in layer between:

```text
successful null result
failed tool call
missing result
timeout
```

Async supplier wrappers should catch tool exceptions and encode them into the supplier result expected by the corresponding plan:

```java
Supplier<T> supplier = () -> {
    try {
        return encodeSuccess(tool.call(...));
    } catch (Throwable t) {
        return encodeFailure(t);
    }
};
```

Then `RunnerContext.durableExecuteAllAsync` calls:

```java
Outcome<T> outcome = plans.get(i).materialize(asyncResults.get(i));
```

and persists the result or exception in `finalizeCallAt`.

---

## Timeout Semantics

A batch timeout should allow collect-all fan-in to proceed.

When timeout fires:

1. completed futures are converted to success/failure outcomes;
2. unfinished futures are cancelled best-effort;
3. unfinished tool slots are finalized as timeout failures;
4. the batch can proceed to `ToolResponseEvent` with per-tool errors.

Example:

```text
tool0 completed -> SUCCESS
tool1 still running at timeout -> TIMEOUT failure
tool2 completed -> SUCCESS
```

Durable finalization:

```text
slot[base + 0] = SUCCESS
slot[base + 1] = FAILURE(TimeoutException)
slot[base + 2] = SUCCESS
```

`Future.cancel(true)` only bounds the runtime's wait. It does not guarantee that the underlying blocking HTTP/RPC operation stops immediately. Tools should still configure their own I/O timeout.

---

## ToolCallAction Changes

`ToolCallAction` should not implement durable slot scanning, batch reservation, recovery matching, ordered finalization, or cursor advancement directly. Those responsibilities belong inside `RunnerContext.durableExecuteAllAsync`.

`ToolCallAction` should be refactored into these steps:

1. Parse `ToolRequestEvent`.
2. Resolve tool resources and build deterministic `DurableCallable<ToolResponse>` objects in original `tool_calls` order.
3. Call `ctx.durableExecuteAllAsync(callables)` when parallel execution is enabled.
4. Convert returned tool responses into `ToolResponseEvent` success / error / responses maps.
5. Emit `ToolResponseEvent` in original tool-call order.

Pseudo flow:

```java
List<DurableCallable<ToolResponse>> callables = buildToolCallables(toolRequest, ctx);

List<ToolResponse> responses;
if (parallel && async && callables.size() > 1) {
    responses = ctx.durableExecuteAllAsync(callables);
} else {
    responses = executeSequentially(callables, async, ctx);
}

ctx.sendEvent(buildToolResponseEvent(toolRequest, responses));
```

The important boundary is:

```text
ToolCallAction:
  tool resolution, callable construction, ToolResponseEvent assembly

RunnerContext.durableExecuteAllAsync:
  durable recovery planning, reservePendingBatch, parallel execution,
  ordered finalizeCallAt, advanceCallIndexBy

ContinuationActionExecutor.executeAllAsync:
  async fan-out/fan-in, timeout, Outcome<T> collection
```

This keeps `ToolCallAction` thin and avoids duplicating durable execution logic in the plan layer.

---

## Configuration

Possible configuration options:

```text
tool-call.parallel = true | false
tool-call.parallel.timeout = duration
tool-call.parallel.max-concurrency = integer
```

Concurrency can be bounded by:

1. a dedicated tool execution pool;
2. a per-batch max concurrency limit;
3. the existing async execution pool, if no dedicated pool is introduced.

A dedicated tool execution pool is preferable because one large tool batch should not starve unrelated async actions.

---

## Java Runtime Requirements

The Java implementation needs:

1. batch planning over a contiguous durable slot range;
2. `reservePendingBatch` support;
3. `finalizeCallAt` support;
4. `advanceCallIndexBy(N)` support;
5. batch async execution in the continuation executor;
6. collect-all `Outcome<T>` representation;
7. timeout fan-in behavior.

For JDK 21 continuation execution, the continuation yields while the batch is pending and resumes when all submitted work completes or timeout fires.

For JDK < 21, the fallback behavior may remain serial unless the runtime introduces a continuation-like async coordination layer.

---

## Python Runtime Requirements

Python needs equivalent semantics, not just a Java-only batch path.

The Python runtime currently has a concern: `await` tends to fuse execution and recording. If `_record_call_completion` only records the current call and has no absolute index, Python cannot directly mirror ordered batch finalization.

Python likely needs:

1. planning over a contiguous durable slot range;
2. ability to reserve N PENDING slots;
3. ability to finalize a slot by absolute index;
4. submit-all / yield-until-all / record-in-tool-call-order behavior;
5. collect-all outcome representation matching Java.

---

## Open Questions / Review Points

### 1. Python parity

The Java sketch is clear, but Python still needs the equivalent flow.

Today, `await` fuses execute and record, and `_record_call_completion` has no absolute index. Python likely needs the same index-addressable primitives plus a submit-all -> yield-until-all -> record-in-tool-call-order path.

Questions:

- Should Python introduce `reservePendingBatch` and `finalizeCallAt` equivalents?
- How should Python avoid recording completion order instead of original tool-call order?
- Should Python use the same `Outcome` concept for collect-all tool results?
- How should Python recover PENDING slots without reconcilers?

### 2. Timeout fan-in

When the batch timeout fires, unfinished calls should be finalized as timeout failures so collect-all can proceed.

Questions:

- Should unfinished slots be finalized via `finalizeCallAt(..., TimeoutException)`?
- Should timeout be per-batch only in v1, or should there also be per-tool timeout?
- Should timeout failures be represented as normal per-tool errors in `ToolResponseEvent`?
- How should docs explain that `cancel(true)` bounds waiting but may not stop blocking external I/O?

### 3. RunnerContext outcome materialization

The low-level continuation executor API returns `List<T>`:

```java
public <T> List<T> executeAllAsync(
        ContinuationContext ctx,
        List<Supplier<T>> suppliers,
        Duration timeout)
        throws Exception;
```

Therefore, failure/null disambiguation must be handled at the `RunnerContext.durableExecuteAllAsync` layer. The `Plan<T>` abstraction should materialize each raw async result, cached slot, reconciler result, or timeout into an `Outcome<T>` before `finalizeCallAt`.

Questions:

- What encoded value should suppliers return when a tool throws?
- Should `Plan.materialize(...)` be the only place that converts raw async results into `Outcome<T>`?
- Should timeout be represented as a synthetic `Outcome.failure(new TimeoutException(...))` before finalization?

---

## Alternatives Not Adopted

### One Composite Durable Batch Slot

This design does not adopt a single durable batch slot with an internal `request-index -> result` map.

Reason:

- it introduces a new composite durable call state model;
- it is a larger deviation from the existing cursor-based durable execution model;
- the current goal can be achieved by extending the existing per-call slot model with batch reservation and index-addressable finalization.

### Internal Event Fan-Out / Fan-In

This design does not fan one `ToolRequestEvent` out into multiple internal tool-call events/actions.

Reason:

- it requires correlation ids, aggregator state, internal events, and additional recovery semantics;
- it significantly changes the `ToolCallAction` execution model;
- it is better treated as a future generic fan-out/fan-in runtime abstraction, not as the v1 tool-call latency improvement.

---

## Invariants

The implementation should preserve these invariants:

1. Tool callable execution happens on async worker threads.
2. Durable state updates happen only on the mailbox thread.
3. Each tool-call index maps to one durable slot.
4. Durable slots are finalized in original tool-call order.
5. Final `ToolResponseEvent` is emitted only after every index has a terminal outcome.
6. Final response order follows original `tool_calls` order.
7. Recovery reuses persisted terminal slots.
8. Missing or PENDING outcomes are re-executed or reconciled.
9. Tool exceptions are represented as per-tool failure outcomes, not thrown as batch failure.
10. Timeout produces per-tool timeout failures for unfinished indexes if timeout is enabled.

---

## Recommended V1 Direction

For v1, prefer:

```text
reserve N durable slots
+ execute N tool calls in parallel
+ call ContinuationActionExecutor.executeAllAsync(...): List<T>
+ materialize raw results into Outcome<T> in RunnerContext
+ finalize slots in original tool-call order
+ advance currentCallIndex by N
+ preserve collect-all ToolResponseEvent semantics
```

The main items to clarify before implementation are:

1. Python parity for reserve-N and index-addressable finalization.
2. Timeout fan-in behavior for unfinished tools.
3. RunnerContext outcome materialization on top of the low-level `List<T>` executor API.
