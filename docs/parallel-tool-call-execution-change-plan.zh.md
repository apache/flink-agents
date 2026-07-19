# 并行工具调用执行改动说明

本文根据 `docs/design.md` 整理项目需要变更的模块、接口、执行路径和测试项，用于实现前审阅。本文只描述设计文档中已有的改动范围，不扩展额外功能。

## 当前实现状态

- [x] Java RunnerContext 逻辑与配置项已完成，待 review。
  - 已新增 `tool-call.parallel`、`tool-call.num-async-threads`、`tool-call.batch.timeout` 配置项。
  - 已新增 `RunnerContext.durableExecuteAllAsync` 与 `Outcome` API。
  - 已实现 Java runtime 批次持久化执行、绝对索引 durable 原语、JDK 11 回退与 JDK 21 批次异步执行入口。
  - 已通过 `mvn -pl runtime -am -DskipTests compile` 编译验证。
- [x] ToolCallAction Java 侧并行调用集成已完成，待 review。
  - 已在 `ToolCallAction` 中接入 `tool-call.async` / `tool-call.parallel` 分支。
  - 已在并行路径使用 `RunnerContext.durableExecuteAllAsync`。
  - 已将工具 durable functionId 从共享 `tool-call` 调整为 `tool-call-{id}`。
  - 已保持缺失工具在构造阶段内联返回错误，不提交异步任务。
  - 已通过 `mvn -pl plan -am -DskipTests package` 编译验证。
- [x] Python parity 已完成，待 review。
  - 已新增 `AgentExecutionOptions.TOOL_CALL_PARALLEL`、`TOOL_CALL_NUM_ASYNC_THREADS` 与 `TOOL_CALL_BATCH_TIMEOUT`。
  - 已新增 Python `DurableCall`、`Outcome` 与 `RunnerContext.durable_execute_all_async` API。
  - 已实现 Python runtime 批次持久化执行、绝对索引 durable 原语桥接、批次并发 await 入口。
  - 已对齐 collect-all 语义：单工具异常会返回对应槽位 `Outcome.failure`；批次超时时已完成槽位保留结果，未完成槽位返回 timeout failure，不直接中断整个批次。
  - 已在 Python `tool_call_action` 中接入 `tool-call.async` / `tool-call.parallel` 分支。
  - 已通过 targeted Python tests、Ruff 和 Java runtime 编译验证。
- [ ] 用户文档与完整测试覆盖待补充。

## 1. 改动目标

在单个 `ToolRequestEvent` 内并行执行多个工具调用，使多个独立 I/O 工具调用的端到端耗时从各工具耗时之和降低为接近最大单个工具耗时。

改动需要满足：

1. 保留 Flink mailbox 线程模型：内存访问、`sendEvent`、持久化状态记录仍在 mailbox 线程上执行。
2. 保持细粒度持久化执行兼容：按确定性调用顺序记录并恢复每个工具调用结果。
3. 支持每个工具独立的 `reconciler()`，用于恢复 `PENDING` 状态的外部请求。
4. 在 JDK < 21 或 `tool-call.async=false` 时回退到现有串行执行路径。
5. 说明并行批次 failover 时可能增加未持久化外部调用数量，要求工具幂等或通过 reconciler 去重。
6. 引入工具执行专用线程池和批次超时，避免工具批次耗尽全局异步线程池。

## 2. API 与配置变更

### 2.1 新增配置项

模块：`api`

文件：`api/src/main/java/org/apache/flink/agents/api/agents/AgentExecutionOptions.java`

新增配置：

```java
public static final ConfigOption<Boolean> TOOL_CALL_PARALLEL =
        new ConfigOption<>("tool-call.parallel", Boolean.class, true);
```

配置行为：

| 配置 | 行为 |
| --- | --- |
| `tool-call.async=false` | 串行同步执行，保持现有行为 |
| `tool-call.async=true`, `tool-call.parallel=false` | 串行异步执行，保持现有行为 |
| `tool-call.async=true`, `tool-call.parallel=true` | 并行异步批次执行，新增行为 |

`num-async-threads` 不再作为工具执行的唯一并发控制。工具执行引入独立线程池：

```java
public static final ConfigOption<Integer> TOOL_CALL_NUM_ASYNC_THREADS =
        new ConfigOption<>("tool-call.num-async-threads", Integer.class, ...);

public static final ConfigOption<Duration> TOOL_CALL_BATCH_TIMEOUT =
        new ConfigOption<>("tool-call.batch.timeout", Duration.class, ...);
```

| 配置 | 行为 |
| --- | --- |
| `tool-call.num-async-threads` | 工具执行专用线程池大小，限制全局工具调用并发 |
| `tool-call.batch.timeout` | 单个工具批次的整体超时时间 |

### 2.2 RunnerContext 新增批次持久化异步接口

模块：`api`

文件：`api/src/main/java/org/apache/flink/agents/api/context/RunnerContext.java`

新增方法：

```java
<T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables) throws Exception;
```

接口语义：

1. 输入为按确定性顺序构造的 `DurableCallable` 列表。
2. JDK 21+ 下，将未命中的 callable 批量提交到异步线程池。
3. Continuation 让出一次，直到批次中所有异步任务完成。
4. 恢复后在 mailbox 线程按输入顺序记录持久化结果。
5. JDK < 21 下回退为串行 `durableExecute(DurableCallable)` 循环。
6. 批次执行受 `tool-call.batch.timeout` 约束。
7. callable 内部不得访问 memory，也不得调用 `sendEvent`。

## 3. Java Runtime 变更

### 3.1 ContinuationContext 扩展批次 pending 状态

模块：`runtime`

文件：

- `runtime/src/main/java21/org/apache/flink/agents/runtime/async/ContinuationContext.java`
- `runtime/src/main/java/org/apache/flink/agents/runtime/async/ContinuationContext.java`

JDK 21 实现新增批次屏障状态：

```java
private volatile Future<?> pendingBatchFuture;

public boolean hasPendingAsync() {
    return isPending(pendingFuture) || isPending(pendingBatchFuture);
}
```

JDK 11 基线类保持可编译的回退实现。

### 3.2 ContinuationActionExecutor 支持批次异步执行

模块：`runtime`

文件：

- `runtime/src/main/java21/org/apache/flink/agents/runtime/async/ContinuationActionExecutor.java`
- `runtime/src/main/java/org/apache/flink/agents/runtime/async/ContinuationActionExecutor.java`

JDK 21 实现新增：

```java
public <T> List<T> executeAllAsync(
        ContinuationContext context,
        List<Supplier<T>> suppliers,
        Duration timeout) throws Exception;
```

执行语义：

1. 将所有 supplier 提交到工具执行专用异步线程池。
2. 创建批次 barrier，等待全部 Future 完成。
3. 将 barrier 写入 `ContinuationContext.pendingBatchFuture`。
4. barrier 未完成且未超过批次 timeout 时通过 Continuation yield，让 mailbox 线程处理其他 Action 任务。
5. barrier 完成后清理 `pendingBatchFuture`。
6. 按输入顺序收集并返回结果。

`executeAction` 的 pending 检查从只判断单个 `pendingFuture` 改为判断 `context.hasPendingAsync()`。

JDK 11 基线类提供串行回退或无异步批次能力的兼容实现。

### 3.3 RunnerContextImpl / JavaRunnerContextImpl 实现批次持久化执行

模块：`runtime`

文件：

- `runtime/src/main/java/org/apache/flink/agents/runtime/context/RunnerContextImpl.java`
- `runtime/src/main/java/org/apache/flink/agents/runtime/context/JavaRunnerContextImpl.java`

新增 `durableExecuteAllAsync` 实现，按设计文档分为三个阶段。该实现使用工具专用异步线程池，并受 `tool-call.batch.timeout` 约束。

#### 阶段 1：准备，运行在 mailbox 线程

对输入列表按顺序扫描：

1. 基于当前 `callIndex` 获取对应 `CallResult` 槽位。
2. 如果槽位不存在，加入待提交计划。
3. 如果槽位与当前 callable 的 `functionId` / `argsDigest` 不匹配，清理当前及后续槽位，并从该位置重新执行。
4. 如果槽位为 `SUCCEEDED`，使用缓存结果。
5. 如果槽位为 `FAILED`，使用缓存异常。
6. 如果槽位为 `PENDING`，有 `reconciler()` 则执行 reconcile 计划，否则重新提交 callable。

#### 阶段 2：扇出 + 让出

1. 将待处理 callable 转换为 supplier。
2. 调用 `ContinuationActionExecutor.executeAllAsync`。
3. JDK 21+ 下批量提交到异步线程池并 yield。
4. JDK < 21 下回退为串行执行。

#### 阶段 3：扇入 + 持久化，运行在 mailbox 线程

1. 合并缓存结果与异步结果。
2. 严格按输入 `callables` 顺序记录每个槽位。
3. 调用完成后将 durable call index 前进 N 位。
4. 返回按输入顺序排列的 `List<Outcome<T>>`。

### 3.4 DurableExecutionContext 增加绝对索引原语

模块：`runtime`

并行批次需要先预留 N 个槽位，再在全部工具完成后按顺序回填结果。现有 `appendPendingCall` 和 `finalizeCurrentCall` 只能服务串行单索引推进，因此需要新增：

| 新方法 | 目的 |
| --- | --- |
| `reservePendingBatch(List<String> ids, String digest)` | 在尾部一次性写入 N 个 `PENDING` 记录；游标不移动 |
| `getCallResultFieldsAt(int index)` | 按绝对 index 读取已有调用槽位，用于准备阶段扫描缓存 |
| `finalizeCallAt(int index, ...)` | 在给定绝对 index 写入终态 `SUCCESS` / `FAILURE` |
| `advanceCallIndexBy(int n)` | 整个批次完成并回填后，将游标前进 n 步 |

批次恢复流程固定为：reserve slots → execute in parallel → finalize in order → advance cursor。

## 4. ToolCallAction 变更

模块：`plan`

文件：`plan/src/main/java/org/apache/flink/agents/plan/actions/ToolCallAction.java`

### 4.1 批次构造

`processToolRequest` 读取：

```java
boolean async = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);
boolean parallel = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_PARALLEL);
```

将 `ToolRequestEvent.getToolCalls()` 转换为有序 `List<DurableCallable<ToolResponse>>`。

### 4.2 执行路径

```java
if (async && parallel && callables.size() > 1) {
    results = ctx.durableExecuteAllAsync(callables);
} else {
    results = executeSequentially(callables, async, ctx);
}
```

串行路径继续保持现有语义：

- `tool-call.async=false` 使用 `ctx.durableExecute(callable)`。
- `tool-call.async=true` 且未启用并行时使用 `ctx.durableExecuteAsync(callable)`。

### 4.3 每个工具使用稳定唯一 functionId

当前共享的：

```java
return "tool-call";
```

改为：

```java
return "tool-call-" + toolCallId;
```

其中 `toolCallId` 来自每个 tool call map 中的 `id` 字段，例如：

```java
String toolCallId = String.valueOf(toolCall.get("id"));
```

对应 functionId 示例：

```text
tool-call-call_abc123
```

该变更用于让持久化恢复在工具集合或调用顺序变化时更精确地检测不匹配。

### 4.4 缺失工具处理

`buildCallables` 负责解析工具资源。设计文档要求缺失工具错误在构造阶段内联处理，不提交异步任务。

### 4.5 ToolResponseEvent 构造

工具执行完成后，继续构造并发送一个 `ToolResponseEvent`。外部 `ToolRequestEvent` / `ToolResponseEvent` 传输格式不变。

## 5. Side effects and duplicate calls

并行执行会增加同一批次内 in-flight 的外部工具调用数量。若正在执行的并行批次发生 failover，可能已经有多个外部调用提交成功，但其结果尚未持久化。恢复后，这些调用可能被重新执行，从而提高重复工具调用风险。

文档和配置说明需要明确：

1. 对有副作用的工具，应保证工具本身幂等，或使用业务侧请求 ID 去重。
2. 对无法简单幂等的工具，应实现 reconciler，并确保 reconciler 能处理已提交但未持久化的外部调用。
3. 并行工具执行不改变外部系统语义保证；恢复正确性依赖工具和 reconciler 对重复调用的处理。

## 6. Python 变更

### 6.1 配置项

模块：`python`

文件：`python/flink_agents/api/core_options.py`

新增与 Java 对齐的配置：

```python
TOOL_CALL_PARALLEL = ConfigOption(
    key="tool-call.parallel",
    default_value=True,
)
```

### 6.2 RunnerContext 新增批次接口

文件：

- `python/flink_agents/api/runner_context.py`
- `python/flink_agents/runtime/flink_runner_context.py`

新增 Python 等效接口：

```python
async def durable_execute_all_async(
    self,
    callables: list[DurableCall],
) -> list[Outcome[Any]]: ...
```

Python 侧需要处理三个结构性约束：

1. index 状态完全在 Java 侧，Python 通过 `_j_runner_context` 桥接，现有桥接只有 current index。
2. `_DurableAsyncExecutionResult.__await__` 当前将 execute 与 record 融合，线程池完成后立即记录并推进 current call index。
3. Python Action 没有 asyncio event loop，每个 operator tick 只推进一次 `send(None)`；多个独立 await 只能串行推进。

因此新增 `_DurableBatchAsyncExecutionResult.__await__`：submit-all → 单个 yield loop 等待全部 futures → 按 tool call 顺序通过绝对 index 记录结果。

新增 Python 数据模型：

| 类型 | 语义 |
| --- | --- |
| `DurableCall` | Python 版 durable callable，包含稳定 `id`、callable、参数、可选 reconciler，以及预留的单调用 timeout 字段 |
| `Outcome` | 批次执行结果，包含三态结果以及 `.value` / `.error` |

每个工具使用稳定 id：`f"tool-call-{call_id}"`。

语义与 Java 对齐：

1. 按输入顺序构造确定性 durable call 列表。
2. 对已完成调用返回缓存结果。
3. 对未完成调用并发提交。
4. 恢复后按绝对 index、按输入顺序记录结果。
5. 不使用 `asyncio.gather` 直接绕过框架；应使用 `durable_execute_all_async`。
6. Python 侧通过 Java 暴露的 index-addressable primitives 进行批次预留、读取、回填和游标推进。

### 6.3 Python tool_call_action 更新

文件：`python/flink_agents/plan/actions/tool_call_action.py`

读取：

- `AgentExecutionOptions.TOOL_CALL_ASYNC`
- `AgentExecutionOptions.TOOL_CALL_PARALLEL`

当 `tool-call.async=true`、`tool-call.parallel=true` 且工具数量大于 1 时，使用 `ctx.durable_execute_all_async(...)`；否则保持现有串行路径。

## 7. 恢复逻辑

### 7.1 正常恢复

恢复时，`ToolCallAction.processToolRequest` 重新构造相同顺序的 callable 列表，并调用 `durableExecuteAllAsync`。

槽位处理规则：

| callIndex 槽位状态 | 行为 |
| --- | --- |
| `SUCCEEDED` / 缓存命中 | 返回缓存结果，不提交到异步线程池 |
| `FAILED` / 缓存异常 | 重新抛出缓存异常 |
| `PENDING` 且存在 `reconciler()` | 运行该工具的 reconciler |
| `PENDING` 且不存在 `reconciler()` | 重新提交 callable |
| 未命中 | 纳入本次扇出提交 |

扇入后的持久化记录必须始终遵循 `tool_calls` 列表顺序，不依赖异步任务实际完成顺序。

### 7.2 部分批次故障转移

示例：3 个工具中，工具 0 和 1 已持久化为 `SUCCEEDED`，工具 2 在故障转移时为 `PENDING`。

恢复行为：

1. 槽位 0、1 命中缓存。
2. 槽位 2 重新提交，或运行 reconciler。
3. 槽位 2 完成后记录结果。
4. 槽位 0、1 不重复记录。

### 7.3 调用顺序不匹配

如果恢复时任一 `callIndex` 的 `functionId` / `argsDigest` 不匹配：

1. 清除当前及后续 `CallResult`。
2. 从不匹配点重新执行该批次后续调用。

`functionId = "tool-call-{id}"` 用于提高不匹配检测精度。

## 8. 多版本 JAR 结构

模块：`runtime`

继续使用现有多版本 JAR 模式：

```text
flink-agents-runtime-{version}.jar
├── .../ContinuationActionExecutor.class       # JDK 11：串行回退
└── META-INF/versions/21/
    └── .../ContinuationActionExecutor.class   # JDK 21：批次异步
```

JDK 21 版本提供真正批次异步执行；JDK 11 基线保持串行回退。

## 9. 文档更新

需要更新以下文档：

1. `docs/content/docs/development/tool_use.md`
   - 说明并行工具调用执行行为。
   - 说明 `tool-call.parallel`、工具专用线程池、并发限制和批次超时配置。
2. `docs/content/docs/development/workflow_agent.md`
   - 补充 `durableExecuteAllAsync` / `durable_execute_all_async`。
   - 说明 Python Action 中仍不应直接使用 `asyncio.gather`，应使用框架提供的批次持久化异步 API。
3. `docs/content/docs/operations/configuration.md`
   - 增加 `tool-call.parallel`、`tool-call.num-async-threads`、`tool-call.batch.timeout` 配置项。
   - 明确说明并行执行在 failover 时可能增加重复外部调用风险，要求工具幂等或通过 reconciler 去重。

## 10. 测试计划

按设计文档需要覆盖以下测试：

| 测试 | 验证点 |
| --- | --- |
| 并行延迟 | 3 个工具各 sleep 2 秒，总耗时小于 4 秒，而不是约 6 秒 |
| 让出行为 | 批次让出期间，不同 key 的其他 Action 任务可以完成 |
| 持久化恢复 | 1/3 工具完成后故障转移，恢复后已完成工具使用缓存，未完成工具重新运行 |
| 调用顺序不匹配 | 恢复时 toolCallId 集合不同，清除缓存并重新执行 |
| 协调器 + 并行 | 恢复时多个工具处于 `PENDING`，各自 reconciler 独立调用 |
| 串行回退 | `tool-call.parallel=false` 和 JDK < 21 路径保持现有串行行为 |
| functionId 唯一性 | 同一批次中每个工具使用不同的 `tool-call-{id}` |
| 副作用重复调用 | 并行批次 failover 后，未持久化的 in-flight 工具调用可被重新执行；文档说明幂等 / reconciler 要求 |
| 批次超时 | `tool-call.batch.timeout` 触发时批次按异常路径完成并持久化失败状态 |

## 11. 迁移影响

1. `tool-call.parallel` 默认值为 `true`。
2. JDK 21+ 上，现有多工具批次会自动获得并行行为。
3. `functionId` 从共享的 `"tool-call"` 改为 `"tool-call-{id}"`，会影响滚动升级期间已有的进行中 `ActionState`。
4. 设计文档中的处理方式是将该变化视为调用顺序不匹配，清除并重新执行相关调用。

## 12. 非本次范围

以下内容按设计文档不纳入第一版：

1. 跨不同 `ToolRequestEvent` 的并行性。
2. 框架级别的每工具自动重试。
3. 修改外部 `ToolRequestEvent` / `ToolResponseEvent` 传输格式。
4. 单工具超时配置。
5. 工具调用相关指标：`tool_call_batch_size`、`tool_call_parallel_duration`、`tool_call_parallelism`。
