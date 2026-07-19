# 并行工具调用执行
草案 · 待审阅
相关内容：[讨论 #404 — 细粒度持久化执行](https://github.com/apache/flink-agents/discussions/404)，[讨论 #429 — Java 异步执行](https://github.com/apache/flink-agents/discussions/429)，[讨论 #598 — 持久化执行协调](https://github.com/apache/flink-agents/discussions/598)

---

## 动机
### 背景
Flink Agents 已支持针对高延迟 I/O 的异步持久化执行：

- **Python**：`await ctx.durable_execute_async(...)` 将任务提交至线程池并让出（yield）算子。参见[讨论 #404](https://github.com/apache/flink-agents/discussions/404)。
- **Java (JDK 21+)**：`ctx.durableExecuteAsync(DurableCallable)` 使用 Continuation API，在工作任务运行于异步线程池时让出邮箱线程（mailbox thread）。参见[讨论 #429](https://github.com/apache/flink-agents/discussions/429)。

内置的 `tool_call_action` 监听 `ToolRequestEvent`，查找每个工具资源，通过持久化执行来执行它们，并在批次中所有工具调用处理完成后发出 `ToolResponseEvent`。参见 [tool_use.md](https://../docs/content/docs/development/tool_use.md)。

当 LLM 在单个响应中返回多个工具调用时，每个调用通常是一个独立的 HTTP / MCP / RPC 请求。并行运行它们可以显著降低端到端延迟。

### 当前问题
目前，`ToolCallAction.processToolRequest` **串行**处理工具调用：

```java
for (Map<String, Object> toolCall : toolRequest.getToolCalls()) {
    // ...
    response = toolCallAsync
            ? ctx.durableExecuteAsync(callable)
            : ctx.durableExecute(callable);
}
```

即使在 JDK 21+ 上且 `tool-call.async=true`（默认值），其行为是：

1. 工具 1 启动并调用 `durableExecuteAsync`。
2. Continuation 让出；邮箱线程变为空闲，**可以处理其他 Action 任务**（其他 key，或同一 key 的其他排队 Action）。
3. 当工具 1 完成时，Continuation 恢复 —— 但执行仍在 `for` 循环内部。
4. 工具 2 **直到**工具 1 的 `durableExecuteAsync` 返回后才开始执行。

因此，目前的异步执行实现的是 **Action 间并发**，而不是 **批次内工具并发**。这与[讨论 #429](https://github.com/apache/flink-agents/discussions/429) 一致，其中明确指出：

**串行执行**：多个 `executeAsync` 调用是串行执行的（与 Python 行为一致）。

对于一个包含 N 个独立 I/O 绑定工具的 `ToolRequestEvent`，总延迟大约等于各工具延迟的**总和**，而不是**最大值**。

此外，当前所有工具调用共享相同的持久化 `functionId`：

```java
public String getId() {
    return "tool-call";
}
```

细粒度持久化执行（[讨论 #404](https://github.com/apache/flink-agents/discussions/404)）通过 `(callIndex, functionId, argsDigest)` 来匹配调用。在批次中为每个工具重复使用 `"tool-call"` 使得当调用顺序或批次组成发生变化时，恢复匹配变得脆弱。

### 目标
在单个 `ToolRequestEvent` 内实现**多个工具调用的并行执行**，同时：

1. 保留**邮箱线程模型**（[讨论 #429](https://github.com/apache/flink-agents/discussions/429)）：内存访问、`sendEvent` 和持久化状态记录仍保留在邮箱线程上执行。
2. 保持与**细粒度持久化执行**（[讨论 #404](https://github.com/apache/flink-agents/discussions/404)）兼容：确定性调用顺序以便恢复，每个调用的结果持久化。
3. 可选地支持基于每个工具的**协调器钩子（reconciler hooks）** 用于进行中恢复（[讨论 #598](https://github.com/apache/flink-agents/discussions/598)）。
4. 优雅降级：JDK < 21 和 `tool-call.async=false` 时回退到串行执行。
5. 明确并行批次在 failover 期间可能增加未持久化外部调用数量，并要求用户通过幂等工具或 reconciler 去重处理恢复时的重复调用风险。
6. 引入工具执行专用线程池，避免单个工具批次耗尽全局异步线程池并影响其他 key / operation。

---

## 设计目标
```text
// 目标：LLM 返回 3 个工具调用；所有 HTTP 请求并发运行
// 总延迟 ≈ max(latency_i)，而非 sum(latency_i)

ToolRequestEvent { tool_calls: [call_a, call_b, call_c] }
    │
    ▼
tool_call_action (邮箱线程)
    ├── durableExecuteAllAsync([callable_a, callable_b, callable_c])
    │       ├── 提交 call_a ──> 异步线程池
    │       ├── 提交 call_b ──> 异步线程池   (并发)
    │       └── 提交 call_c ──> 异步线程池
    ├── 让出 (邮箱空闲 → 可运行其他 Action)
    ├── 全部完成后恢复
    └── sendEvent(ToolResponseEvent)
```

| 目标 | 描述 |
| --- | --- |
| 并行性 | 一个批次中的 N 个工具在异步线程池上并发执行 |
| 邮箱安全性 | `RunnerContext`（内存、事件、指标）仅在邮箱线程上访问 |
| 持久化恢复 | 调用结果按确定性顺序持久化；重放时跳过已完成的工具 |
| 向后兼容 | 当并行被禁用或不支持时，串行路径保持不变 |
| 跨语言 | Java 和 Python 遵循相同的语义 |
| 副作用恢复 | 并行批次中多个外部调用可能同时处于 in-flight；恢复时依赖幂等或 reconciler 去重 |
| 并发隔离 | 工具执行使用独立可配置线程池 |

### 非目标（第一版本）
- **跨**不同 `ToolRequestEvent` 的并行性（已由 Flink 并行度 + 异步让出处理）
- 框架级别的每个工具自动重试（参见未来工作；目前由用户在工具主体中处理）
- 单工具超时配置（`DurableCallable` 级别的 timeout）
- 更改外部的 `ToolRequestEvent` / `ToolResponseEvent` 传输格式

---

## API 设计
### 新增配置选项
```java
// AgentExecutionOptions
public static final ConfigOption<Boolean> TOOL_CALL_PARALLEL =
        new ConfigOption<>("tool-call.parallel", Boolean.class, true);
```

| 配置 | 行为 |
| --- | --- |
| `tool-call.async=false` | 串行同步执行（不变） |
| `tool-call.async=true`, `tool-call.parallel=false` | 串行异步执行（当前行为） |
| `tool-call.async=true`, `tool-call.parallel=true` | 并行异步批次执行（新增） |

现有的 `num-async-threads`（[讨论 #429](https://github.com/apache/flink-agents/discussions/429)）从全局层面限制并发异步工作数量。一个包含 N 个工具的批次可能使用该池中的最多 N 个线程。

新增工具执行专用线程池，避免工具批次占满通用异步执行池并影响其他 key / operation：

```java
// AgentExecutionOptions
public static final ConfigOption<Integer> TOOL_CALL_NUM_ASYNC_THREADS =
        new ConfigOption<>("tool-call.num-async-threads", Integer.class, ...);

public static final ConfigOption<Duration> TOOL_CALL_BATCH_TIMEOUT =
        new ConfigOption<>("tool-call.batch.timeout", Duration.class, ...);
```

| 配置 | 行为 |
| --- | --- |
| `tool-call.num-async-threads` | 工具执行专用线程池大小，限制全局工具调用并发 |
| `tool-call.batch.timeout` | 单个工具批次的整体超时时间 |

`num-async-threads` 不再作为工具执行的唯一并发控制。工具执行应使用独立线程池，避免工具批次耗尽通用异步执行池。

### 新增 RunnerContext 方法
```java
public interface RunnerContext {
    /**
     * 并发执行多个持久化调用，并按输入顺序返回结果。
     *
     * <p>在 JDK 21+ 上，将所有未缓存的调用提交到异步线程池，
     * 让出 Continuation 一次直到全部完成，然后在邮箱线程上按列表顺序记录持久化结果。
     *
     * <p>在 JDK < 21 上，回退到串行的 {@link #durableExecute(DurableCallable)}。
     *
     * <p>该 Action 必须是确定性的：在恢复时必须生成相同的 {@link DurableCallable}
     * 实例列表（相同顺序，相同的 {@link DurableCallable#getId()}）。
     * 否则，后续的缓存结果将按现有规则被清除。
     *
     * <p><b>注意：</b>在 callable 内部禁止访问内存和调用 sendEvent。
     */
    <T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables) throws Exception;
}
```

```java
@Override
public <T> List<OutCome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables) throws Exception {
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
        batchTimeout);

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

Python 等效：

```python
async def durable_execute_all_async(
    self,
    callables: list[DurableCall],
) -> list[Outcome[Any]]: ...
```

Python 侧需要解决三个结构性约束：

1. index 状态完全在 Java 侧，Python 只能通过 `_j_runner_context` 桥接读取 / 写入 call index，现有桥接只有 current index。
2. `_DurableAsyncExecutionResult.__await__` 当前将 execute 与 record 融合：线程池完成后立即调用 `_record_call_completion`，并将 `currentCallIndex += 1`。
3. Python Action 没有 asyncio event loop，每个 operator tick 只推进一次 `send(None)`；多个独立 await 只能串行推进，无法在单个 Action 内获得并发。

因此，`durable_execute_all_async([...])` 必须作为 first-class batch entry，返回单个 awaitable，由 `_DurableBatchAsyncExecutionResult.__await__` 在内部完成 submit-all、单个 yield loop 等待全部 futures、再按 tool call 顺序通过绝对 index 记录结果。

Python 侧新增与 `DurableCallable` 对齐的数据模型：

| 类型 | 语义 |
| --- | --- |
| `DurableCall` | Python 版 durable callable，包含稳定 `id`、callable、参数、可选 reconciler，以及预留的单调用 timeout 字段 |
| `Outcome` | 批次执行结果，包含三态结果以及 `.value` / `.error` |

每个工具使用稳定 id：

```python
f"tool-call-{call_id}"
```

### 每个工具的持久化标识
每个工具 callable 必须使用稳定、唯一的 `functionId`：

```java
@Override
public String getId() {
    return "tool-call-" + toolCallId;  // 例如 "tool-call-call_abc123"
}
```

### 批次恢复所需的绝对索引原语
现有持久化执行原语面向串行、单索引推进：

- `appendPendingCall` 只能在尾部追加一个 `PENDING` 记录，并要求 `currentCallIndex == recoveryCallResults.size()`；无法一次性预留多个槽位。
- `finalizeCurrentCall` 只能完成当前游标指向的槽位；无法在整个批次完成后按绝对 index 回填结果。

并行批次需要先预留 N 个槽位，再在全部工具完成后按工具顺序回填。因此需要在 `RunnerContext` / `DurableExecutionContext` 增加显式 index 能力：

| 新方法 | 目的 |
| --- | --- |
| `reservePendingBatch(List<String> ids, String digest)` | 在尾部一次性写入 N 个 `PENDING` 记录；游标不移动 |
| `getCallResultFieldsAt(int index)` | 按绝对 index 读取已有调用槽位，用于准备阶段扫描缓存 |
| `finalizeCallAt(int index, ...)` | 在给定绝对 index 写入终态 `SUCCESS` / `FAILURE` |
| `advanceCallIndexBy(int n)` | 整个批次完成并回填后，将游标前进 n 步 |

批次恢复流程固定为：**reserve slots → execute in parallel → finalize in order → advance cursor**。

---

## 设计方案
### 推荐方案：批次异步持久化 API（扇出 / 扇入）
扩展运行时以提供 `durableExecuteAllAsync`，而不是更改事件模型。`ToolCallAction` 成为一个薄调用层。

| 标准 | 批次 API |
| --- | --- |
| 保留邮箱模型 | ✅ |
| 最小事件模型变更 | ✅ |
| 复用 Continuation + 持久化状态机 | ✅ |
| 实现复杂度 | 中等 |
| 每个工具独立的让出/调度 | 单批次让出 |

批次 API 是推荐的第一步。如果未来需要与其他 Action 进行更细粒度的交错，Action 任务拆分仍是一种有效的发展方向。

---

## 核心组件设计
### ContinuationContext 扩展
```java
public class ContinuationContext {
    private volatile Future<?> pendingFuture;       // 现有：单调用异步
    private volatile Future<?> pendingBatchFuture; // 新增：批次屏障

    public boolean hasPendingAsync() {
        return isPending(pendingFuture) || isPending(pendingBatchFuture);
    }
}
```

`ContinuationActionExecutor.executeAction` 检查 `hasPendingAsync()` 而不是仅检查 `pendingFuture`：

```java
if (context.hasPendingAsync()) {
    return false;  // Action 未完成；邮箱可以处理其他任务
}
// 恢复 continuation
```

### ContinuationActionExecutor.executeAllAsync (JDK 21)
```java
public <T> List<T> executeAllAsync(
        ContinuationContext context,
        List<Supplier<T>> suppliers,
        Duration timeout) throws Exception {

    List<Future<T>> futures = suppliers.stream()
            .map(s -> asyncExecutor.submit(() -> s.get()))
            .toList();

    Future<Void> barrier = CompletableFuture.allOf(
            futures.stream().map(f -> (CompletableFuture<?>) CompletableFuture.supplyAsync(() -> {
                f.join(); return null;
            })).toArray(CompletableFuture[]::new));

    context.setPendingBatchFuture(barrier);

    while (!barrier.isDone()) {
        Continuation.yield(SCOPE);
    }

    context.setPendingBatchFuture(null);
    return futures.stream().map(Future::join).toList();
}
```

### JavaRunnerContextImpl.durableExecuteAllAsync
三个阶段，全部从邮箱线程编排。执行时使用工具专用异步线程池，并受 `tool-call.batch.timeout` 约束：

**阶段 1 — 准备（邮箱线程）**

对于按输入顺序的每个 `DurableCallable`：

1. `tryGetCachedResult(functionId, argsDigest)` — 命中则填充结果槽位。
2. 未命中则添加到 `pendingList` 并带有原始索引。

**阶段 2 — 扇出 + 让出（邮箱发起，池执行）**

1. 将所有待处理的 callable 提交给 `ContinuationActionExecutor.executeAllAsync`。
2. Continuation 让出一次，直到所有 Future 完成或 `tool-call.batch.timeout` 触发。
3. 在让出期间，邮箱处理其他 Action 任务（[讨论 #429](https://github.com/apache/flink-agents/discussions/429) 流程）。

**阶段 3 — 扇入 + 持久化（邮箱线程）**

对于按原始顺序的每个索引：

1. 合并缓存结果 + 异步结果。
2. `recordDurableCompletion(functionId, argsDigest, result, exception)` — **严格按 tool_calls 顺序**。
3. 将有序的结果列表返回给调用者。

### Side effects and duplicate calls
并行执行会增加同一批次内 in-flight 的外部工具调用数量。若正在执行的并行批次发生 failover，可能已经有多个外部调用提交成功，但其结果尚未持久化。恢复后，这些调用可能被重新执行，从而提高重复工具调用的风险。

因此，启用并行工具执行时需要在配置文档中明确说明该副作用，并提醒用户：

1. 对有副作用的工具，应保证工具本身幂等，或使用业务侧请求 ID 进行去重。
2. 对可能无法简单幂等的工具，应实现 reconciler，并确保 reconciler 能正确处理已提交但未持久化的外部调用。
3. 并行工具执行不会改变外部系统的语义保证；恢复正确性依赖工具和 reconciler 对重复调用的处理。

### ToolCallAction 变更
```java
public static void processToolRequest(Event event, RunnerContext ctx) {
    ToolRequestEvent toolRequest = ToolRequestEvent.fromEvent(event);
    boolean async = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);
    boolean parallel = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_PARALLEL);

    List<Map<String, Object>> toolCalls = toolRequest.getToolCalls();
    List<DurableCallable<ToolResponse>> callables = buildCallables(toolCalls, ctx);

    List<ToolResponse> results;
    if (async && parallel && callables.size() > 1) {
        results = ctx.durableExecuteAllAsync(callables);
    } else {
        results = executeSequentially(callables, async, ctx);
    }

    ctx.sendEvent(buildToolResponseEvent(toolRequest, results, ...));
}
```

`buildCallables` 解析工具，内联处理缺失工具错误（不存在的工具不进行异步提交），并分配 `functionId = "tool-call-" + toolCallId`。

---

## 执行流程
### 并行批次 (JDK 21+)
```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ 并行工具批次执行流程                                                         │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  邮箱线程：                                                                  │
│    [扫描缓存] → [提交 N 个任务] ──让出──> [空闲] ──邮件──> [记录]          │
│                                              │                  [sendEvent]  │
│                                              │                               │
│  异步池：         [tool_a HTTP] ──────────┤                               │
│                   [tool_b HTTP] ─ 并行 ─┤                               │
│                   [tool_c HTTP] ──────────┘                               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘

时间线（3 个工具，各 2 秒）：
──────────────────────────────────────────────────────────────────────────────>
邮箱：  [准备] ─让出─> [空闲：其他 Action] ─邮件─> [扇入 + sendEvent]
池：              [tool_a ──────>│]
                   [tool_b ──────>│]  (~2 秒总计，不是 6 秒)
                   [tool_c ──────>│]
```

与当前串行异步对比：

```text
邮箱：  [tool_a 让出] ─> [空闲] ─> [恢复] [tool_b 让出] ─> [空闲] ─> ...
池：              [tool_a 2s]                  [tool_b 2s]           [tool_c 2s]
总计：~6s
```

---

## 恢复逻辑
与[讨论 #404](https://github.com/apache/flink-agents/discussions/404) 和[讨论 #598](https://github.com/apache/flink-agents/discussions/598) 一致。

### 正常恢复
恢复时，`processToolRequest` 重新执行并使用相同的有序 callable 列表调用 `durableExecuteAllAsync`。批次恢复流程为：

1. `reservePendingBatch` 一次性预留 N 个 `PENDING` 槽位。
2. 未命中缓存的工具并行执行。
3. 批次完成后按 tool call 顺序调用 `finalizeCallAt(index, ...)`。
4. 全部槽位回填完成后调用 `advanceCallIndexBy(n)`。


| callIndex 处的槽位状态 | 行为 |
| --- | --- |
| `SUCCEEDED` / 缓存命中 | 返回缓存结果；不提交到池 |
| `FAILED` / 缓存异常 | 重新抛出缓存的异常 |
| `PENDING`（可协调） | 按 [#598](https://github.com/apache/flink-agents/discussions/598) 运行 `reconciler()` |
| 未命中（故障转移前未启动） | 包含在扇出提交中 |

扇入后的记录始终遵循 **tool_calls 列表顺序**，无论之前在尝试中哪些工具先完成。

### 部分批次故障转移
示例：3 个工具，工具 0 和 1 持久化为 `SUCCEEDED`，工具 2 在故障转移发生时处于 `PENDING`：

1. 阶段 1 缓存扫描：槽位 0、1 命中；槽位 2 为 `PENDING` 或未命中。
2. 阶段 2 扇出：仅提交工具 2（或为 `PENDING` 调用协调器）。
3. 阶段 3 扇入：记录槽位 2；槽位 0、1 不重新记录（已在 `callRecords` 中）。

### 调用顺序不匹配
如果恢复时在任何 callIndex 检测到 `functionId` / `argsDigest` 不匹配（[讨论 #404](https://github.com/apache/flink-agents/discussions/404)）：

- 清除当前及后续的 `CallResult` 条目。
- 从不匹配点重新执行整个批次。

使用每个工具的 `functionId = "tool-call-{id}"` 使得当 LLM 在重试之间返回不同的工具集时，不匹配检测更加精确。

### 协调器集成
具有副作用的 HTTP / MCP 工具应根据[讨论 #598](https://github.com/apache/flink-agents/discussions/598) 实现 `DurableCallable.reconciler()`：

```java
new DurableCallable<ToolResponse>() {
    @Override
    public String getId() {
        return "tool-call-" + toolCallId;
    }

    @Override
    public ToolResponse call() throws Exception {
        return toolRef.call(new ToolParameters(arguments));
    }

    @Override
    public Callable<ToolResponse> reconciler() {
        return () -> toolRef.reconcile(new ToolParameters(arguments));
    }
};
```

对于并行批次，每个工具的协调器在恢复期间当其槽位为 `PENDING` 时独立运行。

---

## 实现
### 模块变更
| 模块 | 变更 |
| --- | --- |
| `api` | 添加 `TOOL_CALL_PARALLEL`、工具专用线程池 / 批次超时配置、`RunnerContext.durableExecuteAllAsync` |
| `runtime`
 (java21) | `ContinuationContext.pendingBatchFuture`
、`executeAllAsync`
、更新 `executeAction` |
| `runtime` | `JavaRunnerContextImpl.durableExecuteAllAsync` 含 3 阶段扇出/扇入；新增绝对 index 的 reserve / read / finalize / advance 原语 |
| `runtime`
 (java11) | 回退：串行 `durableExecute`
 循环 |
| `plan` | 重构 `ToolCallAction`
；修复每个工具的 `functionId` |
| `python` | `durable_execute_all_async`、`DurableCall`、`Outcome`；更新 `tool_call_action.py` |

### 多版本 JAR
与[讨论 #429](https://github.com/apache/flink-agents/discussions/429) 相同的模式：

```text
flink-agents-runtime-{version}.jar
├── .../ContinuationActionExecutor.class       # JDK 11：串行回退
└── META-INF/versions/21/
    └── .../ContinuationActionExecutor.class   # JDK 21：批次异步
```

### 迁移
- 默认 `tool-call.parallel=true`：在 JDK 21+ 上，使用多工具批次的现有作业会自动获得并行行为。
- 从共享的 `"tool-call"` 改为 `"tool-call-{id}"` 会影响滚动升级期间的进行中 `ActionState`。缓解措施：将 functionId 变更视为调用顺序不匹配 → 清除并重新执行（安全，至多一次语义）。

---

## 文档更新
- 更新 [tool_use.md](https://../docs/content/docs/development/tool_use.md)：并行工具执行、配置选项。
- 更新 [workflow_agent.md](https://../docs/content/docs/development/workflow_agent.md) 异步执行章节：记录 `durableExecuteAllAsync`，说明 Python Action 中仍不支持 `asyncio.gather`（应使用 `durable_execute_all_async`）。
- 更新 [configuration.md](https://../docs/content/docs/operations/configuration.md)：`tool-call.parallel`、工具专用线程池、批次超时，并明确说明并行执行在 failover 时可能增加重复外部调用风险，要求工具幂等或通过 reconciler 去重。

---

## 测试
| 测试 | 描述 |
| --- | --- |
| 并行延迟 | 3 个工具 × 2 秒 sleep → 总计 < 4 秒（而不是 6 秒） |
| 让出行为 | 批次让出期间，不同 key 的其他 Action 任务完成 |
| 持久化恢复 | 1/3 工具完成后故障转移 → 恢复重放 1，重新运行 2–3 |
| 调用顺序不匹配 | 恢复时不同的 toolCallId 集合 → 缓存清除，完全重新执行 |
| 协调器 + 并行 | 恢复时 2 个工具 `PENDING` → 协调器被独立调用 |
| 串行回退 | `tool-call.parallel=false` 和 JDK < 21 路径不变 |
| functionId 唯一性 | 批次中的每个工具具有不同的 `tool-call-{id}` |
| 副作用重复调用 | 并行批次 failover 后，未持久化的 in-flight 工具调用可被重新执行；文档说明幂等 / reconciler 要求 |
| 批次超时 | `tool-call.batch.timeout` 触发时批次按异常路径完成并持久化失败状态 |

---

## 未来工作
### Action 任务拆分
如果需要更细粒度的交错（工具 B 在工具 A 仍在进行中时启动，**并且**其他不相关的 Action 不必等待整个批次），可演进到上述替代方案中描述的事件驱动拆分模型。

### 框架级别的工具重试
根据[讨论 #404 未来工作](https://github.com/apache/flink-agents/discussions/404)，持久化调用的重试策略可按批次中的每个工具应用。

### 指标
添加指标：`tool_call_batch_size`、`tool_call_parallel_duration`、`tool_call_parallelism`。

