# Completion-Draining 批量工具执行设计

## 背景

并行工具调用最直接的实现方式是 barrier 模型：

```text
提交 N 个 future
等待所有 future 完成
恢复一次 continuation
一次性记录所有结果
```

这种方式无法满足“某个 tool 一完成就立刻持久化结果”的需求。比如：

```text
tool0: 10s
tool1: 1s
tool2: 2s
```

如果使用 all-done barrier：

```text
t=0   提交 tool0/tool1/tool2
t=1   tool1 完成，但 durable state 不会更新
t=2   tool2 完成，但 durable state 不会更新
t=10  tool0 完成，mailbox 恢复，一次性记录所有结果
```

如果在 `t=5` 发生 failover，`tool1` 和 `tool2` 虽然已经在外部执行完成，但结果没有落盘，恢复时仍然需要重新执行。

期望的行为是：

```text
t=0   提交所有 tools
t=1   tool1 完成 -> 唤醒 mailbox -> 记录 outcome[1]
t=2   tool2 完成 -> 唤醒 mailbox -> 记录 outcome[2]
t=5   failover
恢复时发现 outcome[1] 和 outcome[2] 已经存在，只需要重跑 tool0
```

因此，batch executor 需要从 barrier 模型改成 completion-draining 模型。

---

## 目标执行模型

一个 `ToolRequestEvent` 占用一个 durable batch slot：

```text
callIndex = base
functionId = tool-call-batch
status = PENDING
payload = BatchToolCallState
```

batch slot 内部维护每个 tool 的 outcome：

```java
class BatchToolCallState {
    List<ToolCallSpec> calls;
    Map<Integer, ToolCallOutcome> outcomes;
}
```

执行流程：

```text
Phase 1: 在 currentCallIndex 上预留一个 PENDING batch slot。

Phase 2: 将所有未完成的 tools 提交到 async executor。

Phase 3: Mailbox completion-draining loop:
    while 不是所有 tool 都有 outcome:
        yield，直到至少一个 tool 完成或 timeout 触发
        在 mailbox thread 上 drain 已完成的 tool outcomes
        立即更新 batch slot 的 outcomes map

Phase 4:
    当每个 tool 都有 SUCCESS / FAILURE / TIMEOUT 终态 outcome 后，
    finalize batch slot，
    currentCallIndex 前进 1，
    按原始 tool_calls 顺序发送 ToolResponseEvent。
```

最终的 `ToolResponseEvent` 仍然保持 collect-all 语义，但 durable persistence 是增量式的。

---

## 为什么 `pendingFuture` 不够

现有单个 async durable call 的模型大致是：

```java
context.setPendingFuture(future);

while (!future.isDone()) {
    Continuation.yield(SCOPE);
}

context.setPendingFuture(null);
return future.get();
```

scheduler 可以把状态简化成二元判断：

```text
pending future 未完成 -> 不恢复
pending future 已完成 -> 恢复
```

batch 执行需要不同的状态模型：

```text
没有 completion 可处理 -> blocked
有 completion 可处理     -> resume 并 drain
所有 outcome 已收集      -> resume final path
```

因此，`ContinuationContext` 不能只跟踪单个 `pendingFuture` 或一个 all-done barrier future，而需要保存 batch async state。

---

## Batch Async State

在 `ContinuationContext` 中新增 batch state：

```java
class ContinuationContext {
    private volatile BatchAsyncState<?> pendingBatch;
}
```

示例结构：

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

completion 对象：

```java
class BatchCompletion<T> {
    private final int index;
    private final Outcome<T> outcome;
}
```

outcome 对象：

```java
class Outcome<T> {
    private final boolean success;
    private final T result;
    private final Throwable error;
    private final boolean timeout;
}
```

async thread 只负责产生 `Outcome`。它不能直接更新 durable state。

---

## Scheduler Resume State

scheduler 不能再只用简单的 `hasPendingAsync()` boolean 判断。

概念上可以定义成三态：

```java
enum AsyncResumeState {
    BLOCKED,
    READY,
    DONE
}
```

对于 pending batch：

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

`ContinuationActionExecutor.executeAction` 可以这样处理：

```java
if (context.hasPendingBatch() && !context.getPendingBatch().shouldResume()) {
    return false;
}

resumeContinuation();
```

关键语义变化是：

```text
pending batch 即使没有全部完成，只要有 completion 可处理，就应该恢复 continuation。
```

---

## 唤醒 Mailbox

async task 完成后，不应该在 async thread 上直接恢复 continuation。

它应该做四件事：

1. 将成功或失败转换成 `Outcome`。
2. 将 `BatchCompletion` 放入 completion queue。
3. 通知 action scheduler 当前 action 有 async progress。
4. 让 mailbox thread 稍后恢复 continuation。

示例：

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

`notifyAsyncProgress` 的职责是把 action task 重新放回 mailbox：

```text
async thread:
  completionQueue.add(...)
  mailbox.enqueue(actionTask)

mailbox thread:
  executeAction(actionTask)
  发现 completionQueue 非空
  恢复 continuation
  drain completions
  更新 durable state
```

这样可以保持 mailbox-thread 约束：memory、events 和 durable state 只能在 mailbox thread 上访问。

---

## Completion-Draining `executeAllAsync`

batch executor 不应该实现成：

```java
submit all
while (!allDone) {
    Continuation.yield(SCOPE);
}
return allResults;
```

它应该接受一个只在 mailbox thread 上执行的 completion callback：

```java
<T> List<Outcome<T>> executeAllAsync(
        ContinuationContext ctx,
        List<IndexedSupplier<T>> suppliers,
        BiConsumer<Integer, Outcome<T>> onCompletion,
        Duration timeout)
        throws Exception;
```

callback 负责落 durable state：

```java
(index, outcome) -> durableExecutionContext.updateBatchOutcomeAt(
        baseCallIndex,
        index,
        outcome)
```

伪代码：

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

注意：每次 resume 应该 drain 当前所有可用 completions。不需要严格做到一个 tool 完成就单独 resume 一次。

例如 tool1 和 tool2 几乎同时完成：

```text
completionQueue = [tool1, tool2]
resume once
record outcome[1]
record outcome[2]
yield again
```

这仍然满足“tool 完成后尽快落盘”的目标。

---

## Timeout 处理

timeout 不能只依赖 future completion，因为某个卡死的 tool 可能永远不会完成，也就不会产生 completion。

batch state 需要保存 deadline：

```java
long deadlineNanos;
```

`shouldResume()` 应该包含 timeout 判断：

```java
boolean shouldResume() {
    return hasCompletions()
            || isAllCollected()
            || System.nanoTime() >= deadlineNanos;
}
```

但 action 还必须在 deadline 到期时被重新 enqueue。否则可能没有新的 scheduler pass 来观察 timeout。

提交 batch 时注册 timer：

```java
scheduler.scheduleAt(deadline, () -> notifyAsyncProgress(actionTaskId));
```

timeout 流程：

```text
t=0   提交 batch，并注册 t=30 的 timeout
t=30  timer 触发 -> enqueue action
mailbox 恢复 -> state.isTimedOut() == true
取消未完成 futures
将未完成 indexes 记录为 timeout failure
batch 可以按 collect-all 语义 finalize
```

`Future.cancel(true)` 只能限制 continuation 的等待时间，不保证底层 HTTP/RPC 调用立刻停止。因此仍然需要文档说明：tool 自身应该设置 I/O timeout。

---

## Failure Handling

对于 collect-all 工具执行，一个 tool 抛异常不应该导致整个 batch executor 失败。

应该避免这种模式：

```java
Future<T> future = executor.submit(() -> supplier.get());
T result = future.get(); // 抛异常后可能中断整个 batch
```

应该使用这种模式：

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

future 本身通常正常完成；tool 的异常被表示为 `Outcome.failure`。

这可以保留 collect-all 行为：

```text
一个 tool 失败 -> 记录 outcome[i] failure
其他 tools 继续执行
每个 index 都有终态 outcome 后 batch 完成
ToolResponseEvent 包含每个 tool 的 success/error/result 信息
```

---

## Durable Batch Slot 集成

runner context 层可以利用 completion callback 立即持久化每个 tool outcome：

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

恢复时使用已经记录的 outcomes：

```java
for (int i = 0; i < toolCalls.size(); i++) {
    if (!batchState.hasOutcome(i)) {
        submit tool i;
    }
}
```

已经记录 outcome 的 index 不会重新执行。

---

## 幂等性和重复 Completion 防护

continuation 可能恢复多次，恢复流程也可能看到已经存在的 outcome，因此每个 index 的 durable update 应该是幂等的。

推荐规则：

```text
如果 outcome[index] 已经存在：
    如果是相同 outcome，则忽略
    如果是不同 outcome，则拒绝或失败
```

一个有用的 primitive 是：

```java
boolean putOutcomeIfAbsent(int index, Outcome<?> outcome);
```

这样可以防止意外覆盖已经持久化的 tool result。

---

## Continuation State 生命周期

batch execution 会跨越多次 yield，因此 batch state 不能在每次 resume 时重新创建。

executor 应该用下面的方式防止重复提交：

```java
BatchAsyncState<T> state = ctx.getPendingBatchState();
if (state == null) {
    state = submitAll(...);
    ctx.setPendingBatchState(state);
}
```

context 中保存的 state 应该包含：

- futures
- completion queue
- collected outcomes
- deadline
- batch id 或 action task id
- submitted flag

---

## 必须维护的 Invariants

设计中需要明确维护这些 invariants：

1. Tool callable 在 async pool 中执行。
2. Tool completion 会转换成 `Outcome`；异常不会穿透 batch barrier。
3. Async thread 只 enqueue completion 并通知 mailbox。
4. Async thread 不直接修改 durable state、memory 或 event output。
5. Mailbox thread drain completions，并更新 durable batch slot。
6. 每个 request index 最多记录一次。
7. 只有当每个 request index 都有终态 outcome 后，才发送最终 `ToolResponseEvent`。
8. Recovery 会复用已记录的 per-index outcomes，只重新执行缺失的 indexes。
9. 最终 response 顺序按原始 `tool_calls` 顺序，即使 durable partial update 是按完成顺序写入的。

---

## 总结

为了让每个 tool 一完成就立刻持久化结果，continuation batch executor 需要从 all-done barrier 改成 completion-draining 模型：

```text
future 完成
  -> async thread enqueue completion
  -> async thread 通知 mailbox/action scheduler
  -> mailbox 恢复 continuation
  -> mailbox drain completions
  -> mailbox 将 per-index outcome 写入 durable batch slot
  -> 如果 batch 未完成，continuation 再次 yield
```

核心新增组件包括：

- `BatchAsyncState`
- completion queue
- mailbox re-enqueue / async-progress notification
- pending batch 的 `shouldResume()` 语义
- mailbox-thread `onCompletion` callback
- 一个 durable batch slot + per-index outcome map

这样可以在保留 collect-all tool execution 语义的同时，让已经完成的 tool result 在整个 batch 结束前也能跨 failover 保留下来。
