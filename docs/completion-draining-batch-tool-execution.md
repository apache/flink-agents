# Completion-Draining Batch Tool Execution

## Background

For parallel tool execution, a simple batch executor can use a barrier model:

```text
submit N futures
yield until all futures are done
resume once
record all results
```

This is not enough if we want each tool result to be persisted as soon as that tool finishes. For example:

```text
tool0: 10s
tool1: 1s
tool2: 2s
```

With an all-done barrier:

```text
t=0   submit tool0/tool1/tool2
t=1   tool1 completes, but no durable state is updated
t=2   tool2 completes, but no durable state is updated
t=10  tool0 completes, mailbox resumes, all results are recorded together
```

If failover happens at `t=5`, `tool1` and `tool2` have already completed externally, but their results were not persisted, so recovery must re-run them.

The desired behavior is:

```text
t=0   submit all tools
t=1   tool1 completes -> wake mailbox -> record outcome[1]
t=2   tool2 completes -> wake mailbox -> record outcome[2]
t=5   failover
recovery sees outcome[1] and outcome[2], and only re-runs tool0
```

This requires changing the batch executor from a barrier model to a completion-draining model.

---

## Target Execution Model

A `ToolRequestEvent` occupies one durable batch slot:

```text
callIndex = base
functionId = tool-call-batch
status = PENDING
payload = BatchToolCallState
```

The batch slot contains per-tool outcomes:

```java
class BatchToolCallState {
    List<ToolCallSpec> calls;
    Map<Integer, ToolCallOutcome> outcomes;
}
```

Execution flow:

```text
Phase 1: Reserve one PENDING batch slot at currentCallIndex.

Phase 2: Submit all unfinished tools to the async executor.

Phase 3: Mailbox completion-draining loop:
    while not all tool outcomes exist:
        yield until at least one tool finishes or timeout fires
        drain completed tool outcomes on the mailbox thread
        update the batch slot's outcomes map immediately

Phase 4:
    once every tool has a terminal SUCCESS / FAILURE / TIMEOUT outcome,
    finalize the batch slot,
    advance currentCallIndex by 1,
    emit ToolResponseEvent in original tool_calls order.
```

The final `ToolResponseEvent` remains collect-all, but durable persistence is incremental.

---

## Why `pendingFuture` Is Not Enough

The existing single async execution model is roughly:

```java
context.setPendingFuture(future);

while (!future.isDone()) {
    Continuation.yield(SCOPE);
}

context.setPendingFuture(null);
return future.get();
```

The scheduler can treat the state as binary:

```text
pending future not done -> do not resume
pending future done     -> resume
```

A batch needs a different state model:

```text
no completion available -> blocked
some completions ready  -> resume and drain them
all outcomes collected  -> resume final path
```

Therefore, `ContinuationContext` needs to track a batch async state rather than only a single `pendingFuture` or an all-done barrier future.

---

## Batch Async State

Introduce a batch state object in `ContinuationContext`:

```java
class ContinuationContext {
    private volatile BatchAsyncState<?> pendingBatch;
}
```

Example structure:

```java
final class BatchAsyncState<T> {
    private final int total;
    private final BlockingQueue<BatchCompletion<T>> completions = new LinkedBlockingQueue<>();
    private final Map<Integer, Outcome<T>> outcomes = new ConcurrentHashMap<>();
    private final List<Future<?>> futures;
    private final long deadlineNanos;

    void complete(int index, Outcome<T> outcome) {
        if (outcomes.putIfAbsent(index, outcome) == null) {
            completions.add(new BatchCompletion<>(index, outcome));
        }
    }

    boolean hasCompletions() {
        return !completions.isEmpty();
    }

    BatchCompletion<T> pollCompletion() {
        return completions.poll();
    }

    boolean isAllCollected() {
        return outcomes.size() == total;
    }

    boolean isTimedOut() {
        return System.nanoTime() >= deadlineNanos;
    }

    boolean shouldResume() {
        return hasCompletions() || isAllCollected() || isTimedOut();
    }
}
```

Completion object:

```java
class BatchCompletion<T> {
    private final int index;
    private final Outcome<T> outcome;
}
```

Outcome object:

```java
class Outcome<T> {
    private final boolean success;
    private final T result;
    private final Throwable error;
    private final boolean timeout;
}
```

The async thread only produces `Outcome` objects. It must not update durable state directly.

---

## Scheduler Resume State

The scheduler needs more than a boolean `hasPendingAsync()` check.

A conceptual state model is:

```java
enum AsyncResumeState {
    BLOCKED,
    READY,
    DONE
}
```

For a pending batch:

```java
AsyncResumeState getAsyncResumeState() {
    if (pendingBatch == null) {
        return DONE;
    }

    if (pendingBatch.hasCompletions()) {
        return READY;
    }

    if (pendingBatch.isAllCollected()) {
        return READY;
    }

    if (pendingBatch.isTimedOut()) {
        return READY;
    }

    return BLOCKED;
}
```

Then `ContinuationActionExecutor.executeAction` should treat pending batch state like this:

```java
if (context.hasPendingBatch() && !context.getPendingBatch().shouldResume()) {
    return false;
}

resumeContinuation();
```

The key semantic change is:

```text
A pending batch that is not fully done may still resume if at least one completion is ready.
```

---

## Waking the Mailbox

When an async task finishes, it should not resume the continuation directly on the async thread.

Instead, it should:

1. Convert success or failure into an `Outcome`.
2. Enqueue a `BatchCompletion`.
3. Notify the action scheduler that the action has async progress.
4. Let the mailbox thread resume the continuation later.

Example:

```java
Future<?> future = asyncExecutor.submit(() -> {
    Outcome<T> outcome;
    try {
        outcome = Outcome.success(supplier.get());
    } catch (Throwable t) {
        outcome = Outcome.failure(t);
    }

    state.complete(index, outcome);
    actionScheduler.notifyAsyncProgress(actionTaskId);
});
```

`notifyAsyncProgress` should enqueue the action task back to the mailbox:

```text
async thread:
  completionQueue.add(...)
  mailbox.enqueue(actionTask)

mailbox thread:
  executeAction(actionTask)
  sees completionQueue not empty
  resumes continuation
  drains completions
  updates durable state
```

This preserves the mailbox-thread rule: memory, events, and durable state are only accessed from the mailbox thread.

---

## Completion-Draining `executeAllAsync`

The batch executor should not be implemented as:

```java
submit all
while (!allDone) {
    Continuation.yield(SCOPE);
}
return allResults;
```

Instead, it should accept a mailbox-thread completion callback:

```java
<T> List<Outcome<T>> executeAllAsync(
        ContinuationContext ctx,
        List<IndexedSupplier<T>> suppliers,
        BiConsumer<Integer, Outcome<T>> onCompletion,
        Duration timeout)
        throws Exception;
```

The callback is invoked only on the mailbox thread:

```java
(index, outcome) -> durableExecutionContext.updateBatchOutcomeAt(
        baseCallIndex,
        index,
        outcome)
```

Pseudo implementation:

```java
public <T> List<Outcome<T>> executeAllAsync(
        ContinuationContext ctx,
        List<IndexedSupplier<T>> suppliers,
        BiConsumer<Integer, Outcome<T>> onCompletion,
        Duration timeout)
        throws Exception {

    BatchAsyncState<T> state = ctx.getPendingBatchState();
    if (state == null) {
        state = submitAll(suppliers, timeout);
        ctx.setPendingBatchState(state);
    }

    while (!state.isAllCollected()) {
        if (!state.shouldResume()) {
            Continuation.yield(SCOPE);
        }

        BatchCompletion<T> completion;
        while ((completion = state.pollCompletion()) != null) {
            onCompletion.accept(completion.index(), completion.outcome());
        }

        if (state.isTimedOut()) {
            for (int index : state.unfinishedIndexes()) {
                state.cancel(index);
                Outcome<T> timeoutOutcome =
                        Outcome.failure(new TimeoutException("Batch tool execution timed out"));
                state.complete(index, timeoutOutcome);
            }
        }

        if (!state.isAllCollected()) {
            Continuation.yield(SCOPE);
        }
    }

    ctx.clearPendingBatchState();
    return state.orderedOutcomes();
}
```

Important detail: each resume should drain all currently available completions. The implementation does not need to resume exactly once per tool. It only needs to resume whenever progress is available.

```text
tool1 and tool2 complete close together
completionQueue = [tool1, tool2]
resume once
record outcome[1]
record outcome[2]
yield again
```

---

## Timeout Handling

A timeout cannot rely only on future completion, because a stuck tool may never complete and therefore may never enqueue a completion.

The batch state should store a deadline:

```java
long deadlineNanos;
```

`shouldResume()` should include timeout:

```java
boolean shouldResume() {
    return hasCompletions()
            || isAllCollected()
            || System.nanoTime() >= deadlineNanos;
}
```

However, the action must also be re-enqueued when the deadline expires. Otherwise, no scheduler pass may happen to observe the timeout.

When submitting the batch:

```java
scheduler.scheduleAt(deadline, () -> notifyAsyncProgress(actionTaskId));
```

Timeout flow:

```text
t=0   submit batch, schedule timeout at t=30
t=30  timer fires -> enqueue action
mailbox resumes -> state.isTimedOut() true
unfinished futures are cancelled
unfinished indexes are recorded as timeout failures
batch can finalize with collect-all semantics
```

`Future.cancel(true)` bounds the wait in the continuation, but it does not guarantee that the underlying HTTP/RPC call stops immediately. Tools should still bound their own I/O with request-level timeouts.

---

## Failure Handling

For collect-all tool execution, a tool exception must not fail the whole batch executor.

Avoid this pattern:

```java
Future<T> future = executor.submit(() -> supplier.get());
T result = future.get(); // throws and can break the whole batch
```

Use this pattern instead:

```java
Future<?> future = executor.submit(() -> {
    Outcome<T> outcome;
    try {
        outcome = Outcome.success(supplier.get());
    } catch (Throwable t) {
        outcome = Outcome.failure(t);
    }

    state.complete(index, outcome);
    scheduler.notifyAsyncProgress(actionTaskId);
});
```

The future itself normally completes successfully; the tool failure is represented as `Outcome.failure`.

This preserves collect-all behavior:

```text
one tool fails -> record outcome[i] failure
other tools continue
batch completes after every index has a terminal outcome
ToolResponseEvent contains per-tool success/error/result maps
```

---

## Durable Batch Slot Integration

The runner context layer can use the completion callback to persist per-tool outcomes immediately:

```java
int base = durableContext.currentCallIndex();

BatchCallState batchState =
        durableContext.getOrReserveBatchCall(
                base,
                functionId,
                argsDigest,
                initialState);

List<Integer> pendingIndexes = batchState.missingOutcomeIndexes();
List<IndexedSupplier<ToolResponse>> suppliers = buildPendingToolSuppliers(pendingIndexes);

List<Outcome<ToolResponse>> outcomes =
        continuationExecutor.executeAllAsync(
                continuationContext,
                suppliers,
                (requestIndex, outcome) -> {
                    durableContext.updateBatchOutcomeAt(base, requestIndex, outcome);
                    batchState.putOutcome(requestIndex, outcome);
                },
                timeout);

if (batchState.hasAllOutcomes()) {
    durableContext.finalizeBatchCallAt(base, batchState.toFinalResult());
    durableContext.advanceCallIndexBy(1);
}
```

Recovery uses the recorded outcomes:

```java
for (int i = 0; i < toolCalls.size(); i++) {
    if (!batchState.hasOutcome(i)) {
        submit tool i;
    }
}
```

Already recorded indexes are not re-executed.

---

## Idempotency and Duplicate Completion Protection

Because a continuation may resume multiple times, and because recovery may observe existing outcomes, per-index durable updates should be idempotent.

Recommended rule:

```text
If outcome[index] already exists:
    ignore the same outcome
    reject or fail on a different outcome
```

A useful primitive is:

```java
boolean putOutcomeIfAbsent(int index, Outcome<?> outcome);
```

This prevents accidental overwrite of a previously persisted tool result.

---

## Continuation State Lifecycle

The batch execution state spans multiple yields. It must not be recreated on every resume.

The executor should guard submission like this:

```java
BatchAsyncState<T> state = ctx.getPendingBatchState();
if (state == null) {
    state = submitAll(...);
    ctx.setPendingBatchState(state);
}
```

This prevents duplicate submission after a continuation resumes.

The context-owned state should include:

- futures
- completion queue
- collected outcomes
- deadline
- batch id or action task id
- submitted flag

---

## Required Invariants

The design should explicitly maintain these invariants:

1. Tool callables run on the async pool.
2. Tool completion is converted to `Outcome`; exceptions are not thrown across the batch barrier.
3. Async threads only enqueue completions and notify the mailbox.
4. Async threads never mutate durable state, memory, or event output.
5. The mailbox thread drains completions and updates the durable batch slot.
6. Each request index is recorded at most once.
7. The final `ToolResponseEvent` is emitted only after every request index has a terminal outcome.
8. Recovery reuses recorded per-index outcomes and only re-executes missing indexes.
9. Final response ordering follows the original `tool_calls` order, even if durable partial updates were recorded in completion order.

---

## Summary

To persist each tool result as soon as it completes, the continuation batch executor should be changed from an all-done barrier to a completion-draining model:

```text
future completes
  -> async thread enqueues completion
  -> async thread notifies mailbox/action scheduler
  -> mailbox resumes continuation
  -> mailbox drains completions
  -> mailbox writes per-index outcome into the durable batch slot
  -> continuation yields again if the batch is not complete
```

The essential new pieces are:

- `BatchAsyncState`
- completion queue
- mailbox re-enqueue / async-progress notification
- `shouldResume()` semantics for pending batches
- mailbox-thread `onCompletion` callback
- one durable batch slot with a per-index outcome map

This allows collect-all tool execution while still preserving completed tool results across failover before the entire batch finishes.
