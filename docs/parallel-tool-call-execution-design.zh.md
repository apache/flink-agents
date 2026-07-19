# 并行 Tool Call 执行系统设计

## 状态

基于 GitHub Discussion #855 的设计草案。本版本不采用后续提出的 one durable batch slot 方案，也不采用 internal event fan-out / fan-in 方案。

本文描述如何在一个 `ToolRequestEvent` 中并行执行多个 tool calls，同时保持 durable execution 语义、mailbox-thread 安全性，以及 collect-all 的 tool response 行为。

---

## 背景与动机

当前 `ToolCallAction` 会串行处理一个 `ToolRequestEvent` 中的多个 tool calls。即使启用了 async durable execution，也仍然是前一个 tool call 完成后，下一个 tool call 才开始执行。

对于 LLM 一次返回多个独立 tool calls 的场景，当前总延迟接近：

```text
sum(latency_i)
```

目标行为是：

```text
max(latency_i)
```

也就是让多个相互独立的 tool calls 并发执行。

---

## 目标

1. 在一个 `ToolRequestEvent` 内并发执行多个 tool calls。
2. 保持 mailbox-thread 安全性：
   - durable state 更新发生在 mailbox thread；
   - memory 访问发生在 mailbox thread；
   - `sendEvent` 发生在 mailbox thread。
3. 保持 collect-all 行为：
   - 单个 tool 失败不能导致整个 batch 失败；
   - `ToolResponseEvent` 仍然包含每个 tool 的 success / error / result 信息。
4. 保持 durable recovery 语义：
   - durable records 保持确定性；
   - failover 后可以复用已经完成的 tool result；
   - 未完成的 tool call 可以重新执行或通过 reconciler 恢复。
5. 最终 response 顺序保持稳定，并兼容已有 tool-call 顺序。
6. Java 和 Python 语义保持一致。

---

## 非目标

1. 不改变外部 `ToolRequestEvent` / `ToolResponseEvent` wire format。
2. 不引入 framework-level 的自动 retry 机制。
3. 不解决通用 fan-out/fan-in workflow orchestration。
4. 不保证 timeout 后一定能停止底层 blocking external I/O。
5. 不引入一个 composite durable batch slot 来承载整个 tool batch。
6. 不把一个 tool request 拆成多个 internal events/actions 来执行。

---

## 高层设计

本设计采用如下 durable execution 形态：

```text
reserve N durable slots
parallel execute N tool calls
finalize N durable slots in original tool-call order
advance durable cursor by N
emit one ToolResponseEvent
```

也就是说，每个 tool call 对应一个 durable call slot：

```text
slot base + 0 -> tool call 0
slot base + 1 -> tool call 1
slot base + 2 -> tool call 2
```

多个 tool calls 可以并发执行，但 durable recording 仍然按照原始 `tool_calls` 顺序确定性写回。

---

## 执行流程

```text
ToolRequestEvent
    |
    v
ToolCallAction
    |
    |-- 按 tool_calls 原始顺序构造 deterministic tool callable list
    |
    |-- 扫描 durable slots [base, base + N)
    |       |-- cached SUCCESS / FAILURE -> 复用
    |       |-- PENDING with reconciler -> reconcile
    |       |-- PENDING without reconciler -> 重新执行
    |       |-- missing -> 执行
    |
    |-- 将 missing slots reserve 为 PENDING
    |
    |-- 将需要执行 / reconcile 的 calls 提交到 async executor
    |
    |-- yield，直到所有 submitted calls 完成或 timeout 触发
    |
    |-- fan in 所有 outcomes
    |
    |-- 按 index 顺序 finalize slots: base, base + 1, ... base + N - 1
    |
    |-- currentCallIndex 前进 N
    |
    |-- 按原始 tool_calls 顺序 emit ToolResponseEvent
```

执行完成顺序可以是任意的；durable finalization 顺序必须是确定性的。

---

## Durable Slot 模型

每个 tool call 都有一个独立 durable slot。

三个 tool calls 的例子：

```text
currentCallIndex = base

slot[base + 0] = tool0 durable call
slot[base + 1] = tool1 durable call
slot[base + 2] = tool2 durable call
```

每个 slot 记录：

```java
class CallResult {
    String functionId;
    String argsDigest;
    Status status; // PENDING, SUCCESS, FAILURE
    byte[] resultPayload;
    byte[] exceptionPayload;
}
```

batch execution 只是对 N 个普通 durable call slots 的运行时编排，而不是引入新的 composite durable call 类型。

---

## Durable Identity

每个 tool call 应生成确定性的 durable identity。

保守的 v1 可以保持已有 function-id 策略，并继续依赖：

```text
functionId + argsDigest + currentCallIndex
```

做 recovery matching。

后续可以进一步讨论是否引入更精细的 per-tool function id，例如：

```java
@Override
public String getId() {
    return "tool-call-" + toolCallId;
}
```

但 function-id 语义变化会影响 recovery 兼容性，可以单独讨论。并行执行本身不依赖这个变化。

---

## 需要的 Durable Primitives

当前 durable execution model 是 cursor-based 且偏串行的。batch execution 需要在连续 call slot 范围上支持有限的 index-addressable 操作。

可能需要的 primitives：

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

关键语义：

1. `reservePendingBatch` 写入连续的一段 PENDING slots。
2. `finalizeCallAt` 在指定 absolute durable call index 写入 terminal result。
3. `advanceCallIndexBy(N)` 只在 N 个 slots 都有 terminal results 后执行。
4. 所有 durable state mutation 都发生在 mailbox thread。

---

## Fresh Execution

对于一个新的 N tool calls batch：

```text
base = currentCallIndex

1. 按原始 tool_calls 顺序构造 callables。
2. reserve PENDING slots [base, base + N)。
3. 将 N 个 tool calls 提交到 async executor。
4. 等待 / yield，直到全部 submitted calls 完成或 timeout 触发。
5. 将每个 result / exception / timeout 转换为 Outcome<T>。
6. 按顺序 finalize slots:
   finalizeCallAt(base + 0, outcome0)
   finalizeCallAt(base + 1, outcome1)
   ...
   finalizeCallAt(base + N - 1, outcomeN-1)
7. advanceCallIndexBy(N)
8. emit 一个 ToolResponseEvent。
```

即使 tool 2 比 tool 0 先完成，durable finalization 仍然按照 tool-call order 执行。

---

## Recovery Flow

恢复时，`ToolCallAction` 从同一个 `ToolRequestEvent` 重新构造相同顺序的 callable list。

对于 `[base, base + N)` 中的每个 slot：

| Slot 状态 | 行为 |
| --- | --- |
| Missing | 提交该 tool call。 |
| Matching SUCCESS | 复用 cached result。 |
| Matching FAILURE | 复用 cached failure，作为 per-tool error。 |
| Matching PENDING with reconciler | 执行 reconciler。 |
| Matching PENDING without reconciler | 重新执行该 tool call。 |
| Mismatch | 从 mismatch index 开始清理并重新执行。 |

Partial recovery 示例：

```text
slot[base + 0] = SUCCESS
slot[base + 1] = SUCCESS
slot[base + 2] = PENDING
```

恢复时复用 tool 0 和 tool 1，只重新执行或 reconcile tool 2。

---

## PENDING 语义

现有的 non-reconcilable single durable call 可能不会写 PENDING record。batch execution 可以有意为 batch 中的所有 calls 预留 PENDING slots。

对于 batch execution：

```text
PENDING + reconciler    -> reconcile
PENDING + no reconciler -> re-execute
```

这使 PENDING 成为合法的 batch reservation state。没有 reconciler 的 PENDING slot 不表示 successful null result，而表示该 tool 没有达到 terminal durable state，需要重新执行。

---

## Continuation Batch Execution

RunnerContext 层 API 负责 durable batch planning、recovery matching、batch reservation、ordered finalization 和 cursor advancement。

RunnerContext-facing API 可以设计为：

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

底层 continuation executor API 只负责 async fan-out / fan-in 和 timeout wait，不承载 durable 语义：

```java
public <T> List<T> executeAllAsync(
        ContinuationContext ctx,
        List<Supplier<T>> suppliers,
        Duration timeout)
        throws Exception;
```

对于 reserve-N 设计，durable finalization 发生在 RunnerContext fan-in 阶段，并严格按照原始 tool-call order 执行。

v1 中，一个简单 barrier 就足够，因为 durable finalization 发生在所有 submitted calls 完成或 timeout 后。

```text
submit all executable calls
yield while batch is pending
resume when all submitted calls complete or timeout fires
fan in ordered outcomes
finalize durable slots in order
```

v1 不需要每个 tool 完成时都唤醒 mailbox。

JDK 21 实现可以使用 continuation yielding：

```java
while (!batchFuture.isDone() && !timedOut()) {
    Continuation.yield(SCOPE);
}
```

pending batch state 应该保存在 `ContinuationContext` 中，让 action executor 知道该 action 正在等待 async work。

---

## Batch Async State

示例 runtime state：

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

`ContinuationActionExecutor.executeAction` 可以像处理 pending async call 一样处理 pending batch：

```java
if (context.hasPendingBatch() && !context.getPendingBatch().shouldResume()) {
    return false;
}

resumeContinuation();
```

---

## Failure Semantics

并行 tool call 应保留 collect-all 行为。

一个 tool 失败应该产生 per-tool failure outcome，而不是让整个 batch 失败。

```text
tool0 -> success
tool1 -> failure
tool2 -> success

ToolResponseEvent:
  responses: tool0, tool2
  errors:    tool1
  success:   tool0=true, tool1=false, tool2=true
```

底层 continuation executor 返回裸的 `List<T>`。collect-all failure semantics 由 `RunnerContext.durableExecuteAllAsync` 构造的 suppliers 负责：每个 supplier 应捕获 tool exception，并返回一个能被对应 `Plan` materialize 成 `Outcome<T>` 的值。

推荐 RunnerContext 内部结构：

```java
class Outcome<T> {
    boolean success;
    T result;
    Throwable error;
    boolean timeout;
}
```

这样可以在 durable fan-in 层避免混淆：

```text
successful null result
failed tool call
missing result
timeout
```

async supplier wrapper 应捕获 tool exception，并将其编码进该 plan 期望的 supplier result：

```java
Supplier<T> supplier = () -> {
    try {
        return encodeSuccess(tool.call(...));
    } catch (Throwable t) {
        return encodeFailure(t);
    }
};
```

然后 `RunnerContext.durableExecuteAllAsync` 调用：

```java
Outcome<T> outcome = plans.get(i).materialize(asyncResults.get(i));
```

并在 `finalizeCallAt` 中持久化 result 或 exception。

---

## Timeout Semantics

batch timeout 应允许 collect-all fan-in 继续进行。

timeout 触发时：

1. 已完成 futures 转换为 success / failure outcomes；
2. 未完成 futures 做 best-effort cancel；
3. 未完成 tool slots finalize 为 timeout failures；
4. batch 可以继续生成带 per-tool errors 的 `ToolResponseEvent`。

示例：

```text
tool0 completed -> SUCCESS
tool1 still running at timeout -> TIMEOUT failure
tool2 completed -> SUCCESS
```

Durable finalization：

```text
slot[base + 0] = SUCCESS
slot[base + 1] = FAILURE(TimeoutException)
slot[base + 2] = SUCCESS
```

`Future.cancel(true)` 只限制 runtime 等待时间，不保证底层 blocking HTTP/RPC 操作立刻停止。tool 自身仍应该配置 I/O timeout。

---

## ToolCallAction 改动

`ToolCallAction` 不应该直接实现 durable slot scanning、batch reservation、recovery matching、ordered finalization 或 cursor advancement。这些职责属于 `RunnerContext.durableExecuteAllAsync`。

`ToolCallAction` 应重构为以下步骤：

1. 解析 `ToolRequestEvent`。
2. 解析 tool resources，并按原始 `tool_calls` 顺序构造 deterministic `DurableCallable<ToolResponse>`。
3. 当启用 parallel execution 时，调用 `ctx.durableExecuteAllAsync(callables)`。
4. 将返回的 tool responses 转换为 `ToolResponseEvent` 的 success / error / responses maps。
5. 按原始 tool-call order emit `ToolResponseEvent`。

伪代码：

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

重要边界是：

```text
ToolCallAction:
  tool resolution, callable construction, ToolResponseEvent assembly

RunnerContext.durableExecuteAllAsync:
  durable recovery planning, reservePendingBatch, parallel execution,
  ordered finalizeCallAt, advanceCallIndexBy

ContinuationActionExecutor.executeAllAsync:
  async fan-out/fan-in, timeout wait
```

这样可以让 `ToolCallAction` 保持轻量，并避免在 plan 层重复实现 durable execution 逻辑。

---

## 配置项

可能的配置项：

```text
tool-call.parallel = true | false
tool-call.parallel.timeout = duration
tool-call.parallel.max-concurrency = integer
```

并发度可以由以下方式限制：

1. dedicated tool execution pool；
2. per-batch max concurrency limit；
3. 如果不引入 dedicated pool，则使用现有 async execution pool。

更推荐 dedicated tool execution pool，避免一个很大的 tool batch 占满 async pool，影响其他 unrelated async actions。

---

## Java Runtime 需求

Java 实现需要：

1. 对连续 durable slot range 做 batch planning；
2. 支持 `reservePendingBatch`；
3. 支持 `finalizeCallAt`；
4. 支持 `advanceCallIndexBy(N)`；
5. continuation executor 支持 batch async execution；
6. collect-all `Outcome<T>` 表示；
7. timeout fan-in 行为。

对于 JDK 21 continuation execution，continuation 在 batch pending 时 yield，并在所有 submitted work 完成或 timeout 触发时 resume。

对于 JDK < 21，除非 runtime 引入 continuation-like async coordination layer，否则可以 fallback 到 serial behavior。

---

## Python Runtime 需求

Python 需要等价语义，而不能只是 Java-only batch path。

当前 Python runtime 的问题是：`await` 倾向于把 execute 和 record 绑定在一起。如果 `_record_call_completion` 只能记录当前 call，且没有 absolute index，则 Python 无法直接实现 ordered batch finalization。

Python 可能需要：

1. 对连续 durable slot range 做 planning；
2. 支持 reserve N 个 PENDING slots；
3. 支持按 absolute index finalize slot；
4. submit-all / yield-until-all / record-in-tool-call-order 行为；
5. 和 Java 一致的 collect-all outcome 表示。

---

## 待澄清点 / Review Points

### 1. Python parity

Java sketch 已经比较清晰，但 Python 仍需要等价流程。

当前 `await` 会把 execute 和 record 融合在一起，而 `_record_call_completion` 没有 absolute index。Python 可能需要同样的 index-addressable primitives，以及 submit-all -> yield-until-all -> record-in-tool-call-order 的路径。

问题：

- Python 是否需要引入 `reservePendingBatch` 和 `finalizeCallAt` 等价能力？
- Python 如何避免按 completion order 记录，而是按原始 tool-call order 记录？
- Python 是否也使用同样的 `Outcome` 概念表达 collect-all tool results？
- Python 如何恢复没有 reconciler 的 PENDING slots？

### 2. Timeout fan-in

batch timeout 触发时，未完成 calls 应该 finalize 为 timeout failures，从而让 collect-all 继续进行。

问题：

- unfinished slots 是否应该通过 `finalizeCallAt(..., TimeoutException)` finalize？
- v1 是否只支持 per-batch timeout，还是也支持 per-tool timeout？
- timeout failure 是否作为普通 per-tool error 放进 `ToolResponseEvent`？
- 文档如何说明 `cancel(true)` 只能限制等待时间，但可能无法停止底层 blocking external I/O？

### 3. RunnerContext outcome materialization

底层 continuation executor API 返回 `List<T>`：

```java
public <T> List<T> executeAllAsync(
        ContinuationContext ctx,
        List<Supplier<T>> suppliers,
        Duration timeout)
        throws Exception;
```

因此，failure / null 的区分需要在 `RunnerContext.durableExecuteAllAsync` 层完成。`Plan<T>` 抽象应该在 `finalizeCallAt` 前，将 raw async result、cached slot、reconciler result 或 timeout materialize 成 `Outcome<T>`。

问题：

- tool 抛异常时，supplier 应返回什么编码值？
- 是否应让 `Plan.materialize(...)` 成为唯一将 raw async result 转换成 `Outcome<T>` 的地方？
- timeout 是否应在 finalization 前表示成 synthetic `Outcome.failure(new TimeoutException(...))`？

---

## 不采纳的替代方案

### One Composite Durable Batch Slot

本设计不采用一个 durable batch slot 内部维护 `request-index -> result` map 的方案。

原因：

- 它引入新的 composite durable call state model；
- 它偏离当前 cursor-based durable execution model 更多；
- 当前目标可以通过扩展已有 per-call slot model，并增加 batch reservation 和 index-addressable finalization 来实现。

### Internal Event Fan-Out / Fan-In

本设计不将一个 `ToolRequestEvent` 拆成多个 internal tool-call events/actions。

原因：

- 需要 correlation id、aggregator state、internal events 和额外 recovery semantics；
- 会显著改变 `ToolCallAction` 的执行模型；
- 更适合作为未来通用 fan-out/fan-in runtime abstraction，而不是 v1 tool-call latency improvement。

---

## Invariants

实现需要保持以下 invariants：

1. Tool callable execution 发生在 async worker threads。
2. Durable state updates 只发生在 mailbox thread。
3. 每个 tool-call index 对应一个 durable slot。
4. Durable slots 按原始 tool-call order finalize。
5. 只有每个 index 都有 terminal outcome 后，才 emit 最终 `ToolResponseEvent`。
6. 最终 response order 遵循原始 `tool_calls` 顺序。
7. Recovery 复用已持久化的 terminal slots。
8. Missing 或 PENDING outcomes 会重新执行或 reconcile。
9. Tool exceptions 表示为 per-tool failure outcomes，而不是 batch failure。
10. 如果启用 timeout，timeout 会为 unfinished indexes 产生 per-tool timeout failures。

---

## 推荐的 V1 方向

v1 推荐采用：

```text
reserve N durable slots
+ execute N tool calls in parallel
+ return List<Outcome<T>> internally
+ finalize slots in original tool-call order
+ advance currentCallIndex by N
+ preserve collect-all ToolResponseEvent semantics
```

实现前主要需要澄清：

1. Python parity：reserve-N 和 index-addressable finalization 如何实现。
2. Timeout fan-in：unfinished tools 如何转成 timeout failures。
3. Return type：内部 API 使用 `Outcome<T>`，而不是 `List<T>`。
