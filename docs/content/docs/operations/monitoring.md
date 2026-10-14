---
title: Monitoring
weight: 3
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

## Metric

### Built-in Metrics

We offer data monitoring for built-in metrics, including input runs, events, actions, execution health, and token usage.

#### Event and Action Metrics

| Scope       | Metrics                                          | Description                                                                      | Type  |
|-------------|--------------------------------------------------|----------------------------------------------------------------------------------|-------|
| **Agent**   | numOfEventProcessed                              | The total number of Events this operator has processed.                          | Count |
| **Agent** | numOfEventProcessedPerSec                        | The number of Events this operator has processed per second.                     | Meter |
| **Agent** | numOfActionsExecuted                             | The total number of actions this operator has executed.                          | Count |
| **Agent** | numOfActionsExecutedPerSec                       | The number of actions this operator has executed per second.                     | Meter |
| **Agent** | numOfInputRunsSucceeded                          | The number of input runs that reached the run-completion boundary.                | Count |
| **Agent** | numOfInputRunsFailed                             | The number of input runs terminated by an unhandled exception.                    | Count |
| **Agent** | inputRunLatencyMs                                | End-to-end input-run latency from entering the agent operator to completion or failure, including time queued behind another input with the same key. | Histogram |
| **Agent** | inputRunQueueLatencyMs                           | Time from entering the agent operator until the input run starts processing. | Histogram |
| **Agent** | inputRunProcessingLatencyMs                      | Time from the input-run start boundary until completion or failure. | Histogram |
| **Agent** | numOfPendingInputEvents                          | Current number of input Events buffered behind an active run with the same key. | Gauge |
| **Agent** | numOfActiveInputRuns                             | Current number of logical input runs that are processing or waiting for asynchronous work. | Gauge |
| **Action**  | action.\<action_name\>.numOfActionsExecuted | The total number of actions this operator has executed for a specific action name. | Count |
| **Action**  | action.\<action_name\>.numOfActionsExecutedPerSec | The number of actions this operator has executed per second for a specific action name. | Meter |
| **Action** | action.\<action_name\>.actionSchedulingLatencyMs | Time from enqueuing the initial Action task until it is selected for execution. | Histogram |
| **Action** | action.\<action_name\>.actionExecutionLatencyMs | End-to-end latency of one logical Action execution, including asynchronous waits and continuations. | Histogram |
| **Action** | action.\<action_name\>.numOfPendingActionTasks | Current number of physical Action task segments waiting to run, including continuations. | Gauge |
| **Action** | action.\<action_name\>.numOfActiveActionExecutions | Current number of logical Action executions that have started but have not reached a terminal state. | Gauge |
| **Action**  | action.\<action_name\>.routingDecisionLatencyMs | Wall-clock time, in milliseconds, spent resolving a model-routing decision when a `ChatRequestEvent` names a `MODEL_ROUTER`. Recorded once per routing decision, including replayed ones; see [Model Routing]({{< ref "docs/development/calling-llms/model_routing#observability" >}}). | Histogram |
| **Agent**   | traceLogTruncatedRecords                          | Number of Trace Log records whose attributes were truncated at `STANDARD` level. Increments once per record, regardless of how many fields were truncated. Use this to adjust truncation limits or select entities at `VERBOSE`. | Count |
| **Agent**   | traceLogWriteFailures                           | Number of Trace Log write attempts for which `append`, `flush`, or both failed. Trace Log writes are best-effort and do not fail the job. | Count |

For a locally observed input run, `inputRunLatencyMs` is split into queueing and processing time at the input-run start boundary. `numOfPendingInputEvents` counts buffered inputs, while `numOfActiveInputRuns` counts logical runs; an asynchronous run remains active while it is waiting for its continuation.

An Action execution can be active while one of its continuation tasks is pending, so `numOfActiveActionExecutions` and `numOfPendingActionTasks` are independent. Action scheduling latency is recorded only for the initial task; continuation queueing does not create another scheduling sample.

Input-run outcomes and all latency samples are process-local. Runs or Action executions already in flight when a task is restored do not produce latency samples because their original timestamps are unavailable. An input Event restored from the pending queue can still produce an outcome and processing-latency sample after it starts in the new task attempt, but it does not produce queue or end-to-end latency. Current-count gauges are rebuilt from Flink state after restore.

#### Execution Metrics

LLM, Tool, and sub-agent metrics describe calls observed by the framework within an Action. The framework reports each call's start and outcome directly to the metric collectors. These reports do not create Events for Action routing. Metrics are collected independently of Trace Log output: changing `trace-log.targets` or `level` does not disable them.

The `model_resource`, `tool`, `skill`, `mcp_server`, and `subagent` scopes are independent key-value scopes directly under an Action. The existing `model` scope remains dedicated to model usage metrics.

| Scope | Metrics | Description | Type |
|-------|---------|-------------|------|
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsSucceeded | The number of framework-observed model invocations that returned successfully. | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsFailed | The number of framework-observed model invocations that failed. | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.llmCallLatencyMs | Latency of each framework-observed model invocation, excluding structured-output parsing and retry wait time. | Histogram |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryCount | The number of additional model invocations initiated when retries are enabled (`max-retries > 0`). Only recorded when at least one retry occurs. See [retry-wait-interval]({{< ref "docs/operations/configuration#core-options" >}}). | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryWaitSec | The total backoff time, in seconds, accumulated when retries are enabled (`max-retries > 0`). Only recorded when at least one retry occurs. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsSucceeded | The number of successful calls to the Tool. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsFailed | The number of failed calls to the Tool. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.toolCallLatencyMs | Time spent invoking the individual Tool, excluding time waiting for other calls in the same parallel batch. | Histogram |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.numOfSkillLoads | The number of terminal explicit `load_skill` calls attributed to the Skill, regardless of outcome. | Count |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.skillLoadLatencyMs | Time spent invoking an explicit `load_skill` call. | Histogram |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsSucceeded | The number of successful Tool calls served by the MCP Server. | Count |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsFailed | The number of failed Tool calls served by the MCP Server. | Count |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.mcpToolCallLatencyMs | Individual Tool invocation latency aggregated across the MCP Server. | Histogram |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.numOfSubagentCallsSucceeded | The number of sub-agent delegations that returned a successful result. | Count |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.numOfSubagentCallsFailed | The number of sub-agent delegations that failed, either while submitting or awaiting the delegation or because the sub-agent returned a failed result. | Count |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.subagentCallLatencyMs | Time the Action observed for one sub-agent delegation, from handing the prompt to the sub-agent until its result is observed. | Histogram |

An LLM metric represents one framework invocation of `ChatModel`. A framework retry that calls the model again produces another LLM outcome and latency sample; retries hidden inside a provider or connection are not observed. Every named Tool execution emits Tool metrics. Skill metrics are emitted only for explicit `load_skill` calls; subsequent Tool calls are not inferred to belong to a Skill. MCP metrics aggregate only Tool executions carrying an explicit MCP Server resource name. A `load_skill` or MCP Tool execution therefore contributes to both its Tool scope and the corresponding Skill or MCP Server scope. A sub-agent delegation the model requests through the reserved `_subagent_` callable name emits Subagent metrics keyed by the registered sub-agent name, and is reported only under the Subagent scope rather than also under a Tool scope. `subagentCallLatencyMs` spans the Action-observed delegation from hand-off to result observation; for delegations dispatched concurrently in one batch, this window includes their overlap.

During fine-grained recovery, a cached durable child result contributes another call outcome to these metrics; cache reuse is not separately identified. A cached LLM result is reported as successful and may produce a near-zero latency sample. A cached Tool result retains its normalized outcome: `ToolResponse.error(...)` counts as failed, while a successful `ToolResponse` or an ordinary return value that can be converted to Tool content counts as successful. A cached Tool result produces no latency sample because its callable was not invoked.

Tool names that are not registered runtime resources are aggregated under the fixed `tool=unknown` scope. Requested Skill names that do not resolve in the runtime registry are similarly aggregated under `skill=unknown`. A `_subagent_` callable name that does not resolve to a registered sub-agent is reported as a Tool and therefore aggregated under `tool=unknown`; the Subagent scope is keyed only by registered sub-agent names, so its cardinality is bounded by the Agent plan. When selected for Trace Log output, the call observations retain the original requested names; metric scope cardinality remains bounded.

Tool outcomes follow the same two-layer contract in Java and Python. Resource preparation and invocation exceptions are failures. A returned `ToolResponse.error(...)` is also a failed Tool execution even though the durable invocation returned normally. Tools can return an explicit `ToolResponse` or an ordinary value that is converted to text: strings are used directly, and other values use JSON when possible, falling back to their string representation. A return value that still cannot be converted to text is reported as a failed Tool execution. A durable-persistence exception is reflected as a Tool failure only when the existing durable execution path exposes it to `ToolCallAction`.

`numOfSkillLoads` counts terminal calls rather than successful loads. In both runtimes, `load_skill` reports an unavailable manager, missing Skill, or missing resource through `ToolResponse.error(...)`; the Tool outcome is therefore failed while the Skill load counter still increments. MCP protocol-level errors follow the same failed Tool outcome contract.

Execution latency is measured from matching start and outcome observations of the same call in the same task attempt. Assigning a call identity before preparation or submission does not start the timer. Invoked Tools retain their own start and completion timestamps, even when the reports arrive after a durable call or parallel batch returns. Queueing and time spent waiting for other calls in the batch are excluded. Outcome counters still update when no matching start is available.

A timeout does not necessarily stop a running Tool. If the callable has not finished when the Action observes its durable result or failure, that observation time fixes the call's terminal timestamp. A later completion cannot extend the latency or add another outcome. This boundary excludes subsequent response processing and reporting delays, but may include a delay before the durable result reaches the Action. A callable that starts after this boundary has no reported start or latency sample, even if it later completes in the background. Cached results and failures before invocation also have no Tool latency sample.

Available per-call outcomes are reported independently of response aggregation, so a later response-processing failure does not repeat or discard them. If a batch raises an ordinary Exception without returning per-call outcomes, known starts may still be observed, but no outcomes are inferred from timestamps or the batch exception. If a Java batch propagates an `Error`, directly or through a `CompletionException`, unresolved prepared calls are reported as failed and the original `Error` is rethrown. Those failures indicate a fatal batch abort; they do not imply that every Tool body ran. These rules describe the framework's observations and do not change timeout handling, durable persistence, or the business `ToolResponseEvent` error contract.

In previous releases, `retryCount` and `retryWaitSec` used the `model.<connection_name>` scope. They now use `model_resource.<resource_name>` so retries are attributed to the configured ChatModel resource. Existing queries and dashboards for these two metrics must use the new scope.

#### Token Usage Metrics

Token usage metrics are automatically recorded when chat models are invoked through `ChatModelConnection`. These metrics help track LLM API usage and costs.

| Scope     | Metrics                                                      | Description                                                                    | Type  |
|-----------|--------------------------------------------------------------|--------------------------------------------------------------------------------|-------|
| **Model** | action.\<action_name\>.model.\<model_name\>.promptTokens     | The total number of prompt tokens consumed by the model within an action.      | Count |
| **Model** | action.\<action_name\>.model.\<model_name\>.completionTokens | The total number of completion tokens generated by the model within an action. | Count |

### How to add custom metrics

In Flink Agents, users implement their logic by defining custom Actions that respond to various Events throughout the Agent lifecycle. To support user-defined metrics, we introduce two new properties: `agent_metric_group` and `action_metric_group` in the RunnerContext. These properties allow users to create or update global metrics and independent metrics for actions. For an introduction to metric types, please refer to the [Metric types documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics/#metric-types).

Metric names listed in the built-in tables above are reserved in their corresponding scopes. Custom metrics must use different names within the same scope.

Here is the user case example:

{{< tabs "Custom Metrics" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):
        start_time = time.time_ns()

        # the action logic
        ...

        # Access the main agent metric group
        metrics = ctx.agent_metric_group

        # Update global metrics
        metrics.get_counter("numInputEvent").inc()
        metrics.get_meter("numInputEventPerSec").mark()

        # Access the per-action metric group
        action_metrics = ctx.action_metric_group
        action_metrics.get_histogram("actionLatencyMs") \
            .update(int(time.time_ns() - start_time) // 1000000)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {

    @Action(EventType.InputEvent)
    public static void firstAction(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        long startTime = System.currentTimeMillis();
        
        // the action logic
        ...
        
        FlinkAgentsMetricGroup metrics = ctx.getAgentMetricGroup();

        metrics.getCounter("numInputEvent").inc();
        metrics.getMeter("numInputEventPerSec").markEvent();

        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        actionMetrics
                .getHistogram("actionLatencyMs")
                .update(System.currentTimeMillis() - startTime);
    }
}
```
{{< /tab >}}

{{< /tabs >}}


### How to check the metrics with Flink executor

Flink agents enable the reporting of metrics to external systems by creating a metric identifier prefix in the format `<host>.taskmanager.<tm_id>.<job_name>.<operator_name>.<subtask_index>`. For an agent operator, `<operator_name>` is the agent name. If the Agent name is unavailable, the operator retains the previous `action-execute-operator` value as a fallback. This changes only the value of the existing `<operator_name>` scope; the Agent-specific metric hierarchy is unchanged. Queries and dashboards that filter on `operator_name=action-execute-operator` must use the Agent name after upgrading. Agent-specific metrics use key-value metric groups (e.g., `action.<action_name>`, `model.<model_name>`) which are exposed as dimensions/labels in reporters that support them (such as Prometheus). Please refer to [Flink Metric Reporters](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/metric_reporters/) for more details.

Additionally, we can check the metric results in the Flink Job WebUI using the metric identifier prefix `<subtask_index>.<operator_name>`.

{{< img src="/fig/operations/metricwebui.png" alt="Metric Web UI" >}}

## Log

The Flink Agents' log system uses Flink's logging framework. For more details, please refer to the [Flink log system documentation](https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/logging/).

### How to add log in Flink Agents

For adding logs in Java code, you can refer to [Flink documentation](https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/logging/#best-practices-for-developers). In Python, you can add logs using `logging`. Here is a specific example:

```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    logging.info("Processing input event: %s", event)
    # the action logic
```

### How to check the logs with Flink executor

We can check the log result in the WebUI of Flink Job:

{{< img src="/fig/operations/logwebui.png" alt="Log Web UI" >}}

## Trace Log

Trace Log records Events, Action executions, and component calls as structured JSON. By default, it records only Events at `STANDARD` level. Configure [`trace-log.targets`]({{< ref "docs/operations/configuration#trace-log-targets" >}}) to select other entities or change their level.

The default output is **SLF4J Trace Log**. Select **File Trace Log** with `trace-log.output-type: FILE` or by setting a non-empty `trace-log.base-dir`. A configured base directory takes precedence over `output-type`.

Trace Log writes are best-effort. An `append` or `flush` failure does not fail Event processing or the Flink job. The first failure is logged at `WARN`, subsequent failures at `DEBUG`, and every failed write attempt increments `traceLogWriteFailures`.

### Recording Targets

To record Events, Actions, and component calls at `STANDARD`, with full attributes for the `search` Tool, configure:

```yaml
agent:
  trace-log:
    targets:
      - scope: ALL
        level: STANDARD
      - scope: {tool: search}
        level: VERBOSE
    output-type: FILE
    base-dir: /tmp/flink-agent-logs
```

This example uses file output so the records can be read locally. Each target combines a `scope` with an optional `level`. The specific Tool target overrides the `ALL` preset. Use `level: "OFF"` to suppress a matching entity. Selecting an entity does not automatically select its parents or children.

See [Trace log targets]({{< ref "docs/operations/configuration#trace-log-targets" >}}) for the complete matching, inheritance, and validation rules. The [Recording Level](#recording-level) section below explains how the selected level affects the recorded data.

### SLF4J Trace Log (Default)

The SLF4J logger uses the category `org.apache.flink.agents.TraceLog`. In Flink's default log4j2 environment, where `log.file` is set, it automatically creates `FlinkAgentsTraceLogAppender` and writes JSON to `{log.file}.trace-log.log`. This file is visible in the Flink Web UI **Logs** tab.

An existing configuration for this logger is respected. If no dedicated logger is configured and `log.file` is absent, records go to the root logger. If automatic appender configuration fails, a warning is logged and the existing logging configuration handles output. For local use with the Trace Tree reader, select `FILE` or configure a dedicated SLF4J appender to emit JSON without log prefixes.

All subtasks on a TaskManager share the default SLF4J destination. Each record therefore carries additional top-level `jobId`, `taskName`, and `subtaskId` fields.

### File Trace Log

File output stores one file per operator subtask in `trace-log.base-dir`:

```text
{trace-log.base-dir}/
├── traces-{jobId}-{taskName}-{subtaskId}.log
├── traces-{jobId}-{taskName}-{subtaskId}.log
└── traces-{jobId}-{taskName}-{subtaskId}.log
```

When `FILE` is selected without a base directory, files are stored under `java.io.tmpdir/flink-agents`.

By default, each record occupies one line of JSONL. Setting `trace-log.pretty-print: true` writes multi-line JSON objects instead, so the file is no longer JSONL.

### JSON Format

Both outputs share the same observation fields: entity identity, relationships, timestamp, status, and attributes. Every record includes the top-level integer `schemaVersion`, currently `1`, identifying its JSON format. The logger adds `level` when writing JSON; it is output metadata, not a field of the `TraceRecord` model. SLF4J output also adds the job and subtask fields described above.

For an Event, `attributes` contains its payload and `entityMetadata` contains its ID and source relationships. These relationships belong to the trace context and are not fields of the Event passed between Actions.

| Field | Meaning |
|-------|---------|
| `schemaVersion` | JSON format version, currently `1`; independent of the Flink Agents release and persisted state formats. |
| `timestamp` | Observation or occurrence time. |
| `level` | Output metadata identifying the resolved `STANDARD` or `VERBOSE` level. |
| `inputRunId`, `businessKey`, `agentName` | Available run identity and agent context. |
| `entityType`, `entityName` | The observed entity's type and name. For an Event, its routing type is the entity name. |
| `executionId`, `parentExecutionId` | Identity of an Action execution or component call, and its containing execution when available. These fields are absent for Events. |
| `entityMetadata` | Identity, relationship, and execution metadata, such as Event ID, producer, upstream Event, triggering Event, configured model, or token usage. |
| `status`, `problemCategory` | Progress or outcome, and optional failure classification. These fields are absent for Events. |
| `attributes` | Event payload or details of the observed execution or call. Only this object is subject to `STANDARD` truncation. |

Incompatible changes to field names, types, or meanings require a new schema version. Adding optional fields does not require a version change; readers can ignore fields they do not use.

When an Action is excluded by the logging configuration, its selected component calls still retain their `parentExecutionId`, even though the Action's own record is absent.

For example, an output Event produced by an Action is recorded as:

```json
{
  "schemaVersion": 1,
  "timestamp": "2024-01-15T10:30:02Z",
  "level": "STANDARD",
  "inputRunId": "run-1",
  "businessKey": "order-1001",
  "agentName": "OrderAgent",
  "entityType": "event",
  "entityName": "_output_event",
  "entityMetadata": {
    "eventId": "output-1",
    "producerExecutionId": "action-1",
    "upstreamEventId": "input-1",
    "upstreamActionName": "process_order"
  },
  "attributes": {"output": {"orderId": "order-1001"}}
}
```

`producerExecutionId` identifies the Action execution that directly produced the Event. `upstreamEventId` identifies the preceding Event in its flow, and `upstreamActionName` identifies the associated Action. A runtime-generated Event may have an upstream Event without an Action producer. These references remain in `entityMetadata` even when `STANDARD` truncates attributes.

An LLM call uses its configured ChatModel Resource as `entityName`:

```json
{
  "schemaVersion": 1,
  "timestamp": "2024-01-15T10:30:01Z",
  "level": "STANDARD",
  "inputRunId": "run-1",
  "businessKey": "order-1001",
  "agentName": "OrderAgent",
  "entityType": "llm",
  "entityName": "primary_model",
  "executionId": "llm-1",
  "parentExecutionId": "action-1",
  "entityMetadata": {
    "model": "qwen-max",
    "usage": {"promptTokens": 120, "completionTokens": 30}
  },
  "status": "success",
  "attributes": {}
}
```

`entityMetadata.model` is the model or deployment configured on the Resource. It is the requested identifier, not a provider-confirmed model identity. Action metadata carries `triggerEventId`, linking the execution to the Event that triggered it.

Successful LLM observations include `entityMetadata.usage` when the returned `ChatResult` contains token counts in `usage`. Trace Log records the available `TokenUsage` counts under `promptTokens` and `completionTokens`, independently of token metric collection. Each count is optional, and zero is retained. When neither count is available, `usage` is omitted. Availability depends on the ChatModel integration; the trace does not fetch additional provider data.

Each retry reports its own response usage without accumulating earlier attempts. A response rejected later because of its finish reason or structured-output parsing still has a successful LLM observation with its usage. Replaying a cached durable response reports that response's usage again; this does not establish that the provider received another request.

One entity can have several observations sharing its identity. Progress and outcome statuses include `created`, `started`, `success`, `failed`, and `reused`; they are not separate Event routing types. Tool calls can be observed at creation before their callable starts. Some observations are published after a durable call or parallel batch returns, while retaining their occurrence timestamp. A missing observation therefore does not establish that the call never ran or completed. Use occurrence timestamps rather than file order to calculate call latency.

For a new component invocation or retry attempt, the `created` and `started` reporting helpers, including timestamped starts, can allocate and return an `executionId`. The caller retains that ID and explicitly passes it to all subsequent reports for the call, including duplicate reports. The underlying `ExecutionReporter` API requires an explicit ID. Overlapping calls with identical type, name, and metadata have different IDs. Metadata may change between reports; correlate the observations by `executionId`.

An ordinary asynchronous wait retains the component call's ID. Failover or replay may create new component call IDs; Action execution IDs and lifecycle behavior retain their existing semantics. Trace output remains best effort and may contain missing or repeated records. An ID does not guarantee exactly-once output, deduplication, or a terminal observation for every call.

### Recording Level

| Level | Behavior |
|--------|----------|
| `OFF` | Write no matching record. |
| `STANDARD` | Truncate or summarize large values under `attributes` using the configured limits. |
| `VERBOSE` | Retain attributes without truncation, after media sanitization. |

`trace-log.standard.max-string-length`, `trace-log.standard.max-array-elements`, and `trace-log.standard.max-depth` control long strings, large arrays, and deep nesting respectively. Setting a threshold to `0` disables that truncation limit. Setting all three to `0` makes `STANDARD` retain the same content as `VERBOSE`, apart from the `level` label.

**Media sanitization.** Both recording level settings (`STANDARD` and `VERBOSE`) omit inline Base64 media data from typed content blocks and remove userinfo, query strings, and fragments from media URLs. This includes blocks in `ChatMessage`, `ChatResult`, Tool results, and other Event attributes. Media metadata is retained. Message text is not automatically redacted.

**Fields preserved in full.** Truncation never changes entity identity, relationships, entity metadata, timestamps, statuses, or problem categories. It applies only under `attributes`, so `STANDARD` retains the relationships needed to correlate the records that were selected.

When content is truncated, its value is replaced with a wrapper:

| Truncated content | Replacement |
|-------------------|-------------|
| Long string | `{"truncatedString": "<first N chars>...", "omittedChars": M}` |
| Large array | `{"truncatedList": [<first N elements>], "omittedElements": M}` |
| Deep object | `{"truncatedObject": {<scalar fields only>}, "omittedFields": N}` |

A truncated field changes JSON type. Consumers requiring the full attribute schema should select that entity at `VERBOSE`. `traceLogTruncatedRecords` increments once per record with truncated attributes, regardless of the number of truncated fields.

### Trace Tree Reader

The `flink-agents-trace-tree` command is installed with the Python wheel. It reconstructs Event causal trees from the `TraceRecord` format described above. Pass a file directly or a directory containing `traces-*.log` or `*.trace-log.log` files. Both JSONL and pretty-printed JSON are supported.

The reader accepts records with integer `schemaVersion: 1`. A missing, invalid, or unsupported version produces a warning and the record is skipped; other records continue to be read.

The reader uses `entityMetadata.eventId`, `upstreamEventId`, and `upstreamActionName` to build an Event–Action–Event graph. An InputEvent with no upstream Event starts a tree; an InputEvent produced by an Action can be a descendant. An edge without an Action name produces a `MISSING_ACTION_NAME` warning and is not linked. The reader does not require Action observations, so the default `EVENT_ONLY` scope is sufficient. Other observed entities are skipped; component calls and their statuses are not displayed in this Event view.

For example:

```bash
flink-agents-trace-tree /path/to/trace-logs --format text
flink-agents-trace-tree /path/to/traces-job-task-0.log --format json
```

For an InputEvent handled by `process_order` that sends an OutputEvent, the text view is:

```text
Trace Tree 1
  _input_event (input-1)
    [Action: process_order]
      _output_event (output-1)
```

The JSON result exposes the same graph:

| Field | Meaning |
|-------|---------|
| `roots` | IDs of root InputEvents, one per reconstructed tree. |
| `nodes` | Event nodes indexed by Event ID. |
| `nodes[id].eventId`, `eventType`, `timestamp` | Event identity, routing type, and the first observed timestamp. The reader's `eventType` comes from the log record's `entityName`. |
| `nodes[id].observationCount` | Number of log observations with this Event ID, including any conflicting observations reported in `warnings`. |
| `nodes[id].upstreamEdges` | Distinct recorded upstream Event and Action references. Consult `warnings` for missing or invalid relationships. |
| `nodes[id].actions` | Actions linked from this Event; each has a `name` and a `children` list of output Event IDs. |
| `warnings` | Problems encountered while reading records or reconstructing relationships. |

Observations with the same Event ID and type share one node and deduplicated edges. Shared descendants can appear under several branches, so the combined graph may be a DAG rather than a strict tree. Attribute differences, including `STANDARD` truncation, do not affect identity or relationships. Sibling order is only a presentation order and does not indicate execution order.

Malformed records, conflicting Event identities, missing upstream Events, and cycles produce warnings. Recoverable records remain available in the JSON result, and cycle edges are removed before rendering. If recording targets filtered an upstream Event, the reader cannot reconstruct that missing part of the tree. Historical formats using `eventType`/`eventAttributes` or a nested `event` object are unsupported.

### Compatibility Notes

These notes compare Trace Log with the original Event Log, which recorded Events only.

- **Configuration keys.** The old `event-log.*`, `eventLoggerType`, `baseLogDir`, and `prettyPrint` settings are rejected at startup. Configure Event selection and recording levels through `trace-log.targets`, truncation through `trace-log.standard.*`, and output through `trace-log.output-type`, `trace-log.base-dir`, and `trace-log.pretty-print`. See [Trace Log Configuration]({{< ref "docs/operations/configuration#trace-log-targets" >}}).
- **Log format.** Records use the Trace Log JSON format with `schemaVersion: 1`; the old nested `event` object is removed.
- **Consumer field mappings.** Update parsers and queries from `logLevel` to `level`, from `eventType` to `entityName` for Events, from `event.id` to `entityMetadata.eventId`, and from `event.attributes` to `attributes`.
- **Output names.** File names change from `events-*.log` to `traces-*.log`. The SLF4J category changes from `org.apache.flink.agents.EventLog` to `org.apache.flink.agents.TraceLog`, the appender from `FlinkAgentsEventLogAppender` to `FlinkAgentsTraceLogAppender`, and the file suffix from `.event-log.log` to `.trace-log.log`. Update log collection and custom log4j2 configuration accordingly.
- **Custom loggers.** Custom `EventLogger` implementations and factory registrations must adapt to `TraceLogger` and `TraceLoggerFactory`. The append contract changes from `append(EventContext, Event)` to `append(TraceRecord, TraceLogLevel)`.
- **Write failures and metrics.** An `append` or `flush` failure no longer propagates into Event processing or fails the job; it is logged and counted in `traceLogWriteFailures`. The truncation metric changes from `eventLogTruncatedEvents` to `traceLogTruncatedRecords` and covers all selected record types. Update dashboards and alert queries accordingly.
- **Python Event identity.** Automatically generated Event IDs now use UUID4 rather than a content-derived UUID. Changing an Event's payload no longer regenerates its ID, and `id` is immutable. Code that relies on equal payloads producing equal IDs or assigns to `event.id` must be updated.
