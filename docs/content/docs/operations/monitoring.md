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


## Overview

A running agent produces three kinds of observability data, and each answers a different question.

- **Metrics** show how much and how fast: Event and Action throughput, input-run outcomes and latency, LLM, Tool and sub-agent calls, and token usage. They go through [Flink's metric system](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics/), so they reach the same reporters and dashboards as the rest of the job.
- **Logs** carry what your Action code and the framework write through Flink's logging framework, such as warnings and stack traces.
- **The [Trace Log](#trace-log)** records Events, Action executions and the calls made within Actions as structured JSON, so you can follow what one input did. By default it records only Events; configure `trace-log.targets` to include Actions and component calls.

A typical investigation starts from a metric that looks wrong, such as a rising `numOfInputRunsFailed`, moves to the logs of the affected task for the error, and then to the Trace Log to see which Events and Actions led there.

## Metrics

Flink Agents registers its metrics with the job's Flink metric system. For the metric types used below (Counter, Meter, Histogram and Gauge), see [Metric types](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics/#metric-types). Latency metrics are in milliseconds, as their `Ms` suffix shows; `retryWaitSec` is in seconds.

### Accessing metrics

Metrics are available wherever the job's other metrics are: through a configured [metric reporter](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/metric_reporters/), such as Prometheus, or in the Flink Web UI (see [Dashboard integration](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics/#dashboard-integration)).

With Flink's default operator scope, metric identifiers start with `<host>.taskmanager.<tm_id>.<job_name>.<operator_name>.<subtask_index>`; reporters may format them differently. For an agent operator, `<operator_name>` is the agent name, or `action-execute-operator` when the agent has no name. In the Flink Web UI, the agent operator's metrics appear under the prefix `<subtask_index>.<operator_name>`.

{{< img src="/fig/operations/metricwebui.png" alt="Metric Web UI" >}}

### Scopes

The **Scope** column in the tables below identifies the metric group. The example names omit the operator prefix shown above; reporters may represent the groups as labels instead.

| Scope | Metric group | Example name |
|-------|--------------|--------------|
| **Agent** | the agent operator itself | `numOfEventProcessed` |
| **Action** | `action.<action_name>` | `action.chat_model_action.numOfActionsExecuted` |
| **Model** | `action.<action_name>.model.<model_name>` | `action.chat_model_action.model.qwen3:1.7b.promptTokens` |
| **Model Resource**, **Tool**, **Skill**, **MCP Server**, **Subagent** | `action.<action_name>.<kind>.<name>`, where `<kind>` is `model_resource`, `tool`, `skill`, `mcp_server` or `subagent` | `action.tool_call_action.tool.get_weather.numOfToolCallsSucceeded` |

Each group below the Agent scope is a key-value group: `<action_name>` is the Action's name, `<model_name>` the model a chat model reports usage for, and `<name>` the resource, Tool, Skill, MCP Server or sub-agent name. Reporters that support key-value groups, such as Prometheus, expose them as labels. The examples use `chat_model_action` and `tool_call_action`, [built-in Actions]({{< ref "docs/development/create-agent/events#built-in-events-and-actions" >}}) that call chat models and Tools, respectively. Their names appear in metric paths when your Agent uses them.

### Built-in Metrics

Flink Agents provides built-in metrics for input runs, Events, Actions, execution health, and token usage. Numbers in brackets refer to the notes below each table.

#### Event and Action Metrics

| Scope | Metrics | Description | Type |
|-------|---------|-------------|------|
| **Agent** | numOfEventProcessed | Events processed by this operator. | Counter |
| **Agent** | numOfEventProcessedPerSec | Events processed per second. | Meter |
| **Agent** | numOfActionsExecuted | Actions executed by this operator. | Counter |
| **Agent** | numOfActionsExecutedPerSec | Actions executed per second. | Meter |
| **Agent** | numOfInputRunsSucceeded | Input runs that completed. [1] | Counter |
| **Agent** | numOfInputRunsFailed | Input runs terminated by an unhandled exception. [1] | Counter |
| **Agent** | inputRunLatencyMs | Time from an input entering the operator until its run completes or fails, including time queued behind an earlier input with the same key. [1] | Histogram |
| **Agent** | inputRunQueueLatencyMs | Time from an input entering the operator until its run starts. [1] | Histogram |
| **Agent** | inputRunProcessingLatencyMs | Time from the start of an input run until it completes or fails. [1] | Histogram |
| **Agent** | numOfPendingInputEvents | Inputs currently waiting behind an active run with the same key. [2] | Gauge |
| **Agent** | numOfActiveInputRuns | Input runs currently processing or waiting for asynchronous work. [2] | Gauge |
| **Action** | action.\<action_name\>.numOfActionsExecuted | Executions of this Action. | Counter |
| **Action** | action.\<action_name\>.numOfActionsExecutedPerSec | Executions of this Action per second. | Meter |
| **Action** | action.\<action_name\>.actionSchedulingLatencyMs | Time from scheduling an Action execution until it starts running. [3] | Histogram |
| **Action** | action.\<action_name\>.actionExecutionLatencyMs | Time for one Action execution, including asynchronous waits. [1] | Histogram |
| **Action** | action.\<action_name\>.numOfPendingActionTasks | Action tasks waiting to run, including continuations of asynchronous Actions. [3] | Gauge |
| **Action** | action.\<action_name\>.numOfActiveActionExecutions | Action executions that have started and not yet finished. [3] | Gauge |
| **Action** | action.\<action_name\>.routingDecisionLatencyMs | Time to resolve a routing decision when a `ChatRequestEvent` names a `MODEL_ROUTER`, including replayed decisions; see [Model Routing]({{< ref "docs/development/calling-llms/model_routing#observability" >}}). | Histogram |
| **Agent** | traceLogTruncatedRecords | Trace Log records whose attributes were truncated at `STANDARD` level, counted once per record. | Counter |
| **Agent** | traceLogWriteFailures | Failed Trace Log writes. Writes are best effort and do not fail the job. | Counter |

1. Input-run outcomes and latency samples are local to a task attempt. Input runs already active when a task is restored produce neither outcome counts nor latency samples, and Action executions already active when the task is restored produce no latency sample. An input restored from the pending queue records its outcome and processing latency once it starts in the new attempt, but not its queue or end-to-end latency.
2. `numOfPendingInputEvents` counts buffered inputs and `numOfActiveInputRuns` counts runs; a run waiting for an asynchronous continuation stays active. Both gauges are rebuilt from Flink state after a restore.
3. An Action execution stays active while one of its continuation tasks is pending, so the two Action gauges move independently. Scheduling latency is recorded only for the first task of an execution.

#### Execution Metrics

These metrics describe LLM, Tool, Skill, MCP Server and sub-agent calls that the framework observes inside an Action. They are collected whatever `trace-log.targets` selects.

| Scope | Metrics | Description | Type |
|-------|---------|-------------|------|
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsSucceeded | Model invocations that returned successfully. [1] [6] | Counter |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsFailed | Model invocations that failed. [1] [6] | Counter |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.llmCallLatencyMs | Time for one model invocation, excluding structured-output parsing and retry waits. [1] | Histogram |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryCount | Additional invocations made by framework retries (`max-retries > 0`); recorded only when a retry occurs. See [retry-wait-interval]({{< ref "docs/operations/configuration#core-options" >}}). | Counter |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryWaitSec | Backoff time accumulated by framework retries; recorded only when a retry occurs. | Counter |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsSucceeded | Successful calls to the Tool. [2] [6] | Counter |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsFailed | Failed calls to the Tool. [2] [6] | Counter |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.toolCallLatencyMs | Time spent in one Tool call, excluding waits for other calls in the same parallel batch. [5] | Histogram |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.numOfSkillLoads | Explicit `load_skill` calls for the Skill, whatever their outcome. [3] | Counter |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.skillLoadLatencyMs | Time for one explicit `load_skill` call. | Histogram |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsSucceeded | Successful Tool calls served by the MCP Server. [3] | Counter |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsFailed | Failed Tool calls served by the MCP Server. [3] | Counter |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.mcpToolCallLatencyMs | Time for one Tool call served by the MCP Server. | Histogram |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.numOfSubagentCallsSucceeded | Sub-agent delegations that returned a successful result. [4] | Counter |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.numOfSubagentCallsFailed | Sub-agent delegations that failed while submitting or awaiting, or returned a failed result. [4] | Counter |
| **Subagent** | action.\<action_name\>.subagent.\<agent_name\>.subagentCallLatencyMs | Time from handing the prompt to the sub-agent until its result is observed. [4] | Histogram |

1. One sample is one framework invocation of `ChatModel`. A framework retry produces another outcome and latency sample; retries inside a provider client are not observed. A response that is later rejected for its finish reason or structured-output parsing still counts as a successful call.
2. A Tool call fails when preparing or invoking it raises an exception, when it returns `ToolResponse.error(...)`, or when its return value cannot be converted to text. Tool names that are not registered resources are counted under `tool=unknown`.
3. A `load_skill` call or an MCP Tool call is also counted in its Tool scope. `numOfSkillLoads` increments even when the load fails, for example for a missing Skill. Skill names that do not resolve are counted under `skill=unknown`.
4. Only delegations the model requests through the reserved `_subagent_` name are reported, under the Subagent scope keyed by the registered sub-agent name and not also under a Tool scope. A `_subagent_` name that does not resolve is counted under `tool=unknown`. For delegations dispatched together in one batch, the latency includes their overlap.
5. A Tool call has a latency sample only when its callable started in the current task attempt.
6. During recovery, a result replayed from durable state counts as another outcome, without implying another provider request or Tool invocation. A replayed LLM response keeps its outcome and may produce a near-zero latency sample; a replayed Tool result keeps its outcome and has no latency sample.

#### Token Usage Metrics

Token usage metrics are recorded when chat models are invoked through `ChatModelConnection`, which helps track LLM API usage and cost. In the built-in chat flow, token usage metrics appear under `action.chat_model_action.model.<model_name>`, not under the Action that sends `ChatRequestEvent`.

| Scope | Metrics | Description | Type |
|-------|---------|-------------|------|
| **Model** | action.\<action_name\>.model.\<model_name\>.promptTokens | Prompt tokens consumed by the model within an Action. | Counter |
| **Model** | action.\<action_name\>.model.\<model_name\>.completionTokens | Completion tokens generated by the model within an Action. | Counter |

### How to add custom metrics

Actions can register their own metrics through the `RunnerContext`: `agent_metric_group` (Java `getAgentMetricGroup()`) for metrics of the whole operator, and `action_metric_group` (Java `getActionMetricGroup()`) for metrics of the current Action. Names used by the built-in metrics are reserved in their scope, so custom metrics need different names there.

{{< tabs "Custom Metrics" >}}

{{< tab "Python" >}}
```python
import time

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

## Logs

In a Java Action, log through SLF4J as in any Flink code; those logs follow the job's [Flink logging configuration](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/advanced/logging/). In a Python Action, use the standard `logging` module.

{{< tabs "Logging in an Action" >}}

{{< tab "Python" >}}
```python
import logging

logger = logging.getLogger(__name__)

class MyAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        logger.info("Processing input: %s", InputEvent.from_event(event).input)
        # the action logic
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    private static final Logger LOG = LoggerFactory.getLogger(MyAgent.class);

    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        LOG.info("Processing input: {}", InputEvent.fromEvent(event).getInput());
        // the action logic
    }
}
```
{{< /tab >}}

{{< /tabs >}}

You can read the logs of a running job in the Flink Web UI:

{{< img src="/fig/operations/logwebui.png" alt="Log Web UI" >}}

## Trace Log

Trace Log records Events, Action executions, and component calls as structured JSON. By default, it records only Events at `STANDARD` level. Configure [`trace-log.targets`]({{< ref "docs/operations/configuration#trace-log-targets" >}}) to select other entities or change their level.

Trace Log writes are best effort. A failed write does not fail Event processing or the job; the first failure is logged at `WARN`, later ones at `DEBUG`, and each one increments `traceLogWriteFailures`.

### Recording Targets

A target's `scope` supports `EVENT_ONLY` (the default), which records only Events, and `ALL`, which records all entity types the framework observes. The example below uses `ALL`.

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

### Output

The default output is **SLF4J**. Select **file** output with `trace-log.output-type: FILE` or by setting a non-empty `trace-log.base-dir`; a configured base directory takes precedence over `output-type`.

**SLF4J (default).** Records go to the logger category `org.apache.flink.agents.TraceLog`. In Flink's default log4j2 setup, where `log.file` is set, Flink Agents adds an appender that writes them as plain JSON to `{log.file}.trace-log.log`; in the Flink Web UI, the file is listed in the TaskManager's **Log List**, and it can be passed directly to the [Trace Tree reader](#trace-tree-reader). An existing configuration for this logger is respected. If no dedicated logger is configured and `log.file` is absent, records follow the inherited logging configuration, normally the root logger; a custom appender should write JSON without log prefixes if you want to read it with the Trace Tree reader. If the automatic appender cannot be configured, a warning is logged and the existing logging configuration handles output. All subtasks on a TaskManager share this destination, so each record also carries `jobId`, `taskName` and `subtaskId`.

**File.** File output writes one file per operator subtask in `trace-log.base-dir`, or under `java.io.tmpdir/flink-agents` when no base directory is set:

```text
{trace-log.base-dir}/
├── traces-{jobId}-{taskName}-0.log
├── traces-{jobId}-{taskName}-1.log
└── traces-{jobId}-{taskName}-2.log
```

Each record is one line of JSONL. Setting `trace-log.pretty-print: true` writes multi-line JSON objects instead, so the file is no longer JSONL.

### JSON Format

Every record has the top-level integer `schemaVersion`, currently `1`. Incompatible changes to field names, types or meanings require a new schema version; added optional fields do not, and readers can ignore fields they do not use.

| Field | Meaning |
|-------|---------|
| `schemaVersion` | JSON format version, currently `1`; independent of the Flink Agents release and persisted state formats. |
| `timestamp` | Observation or occurrence time. |
| `level` | The resolved `STANDARD` or `VERBOSE` level. |
| `inputRunId`, `businessKey`, `agentName` | Available run identity and agent context. |
| `entityType`, `entityName` | The observed entity's type and name. For an Event, its routing type is the entity name. |
| `executionId`, `parentExecutionId` | Identity of an Action execution or component call, and its containing execution when available. These fields are absent for Events. |
| `entityMetadata` | Identity, relationship, and execution metadata, such as Event ID, producer, upstream Event, triggering Event, configured model, or token usage. |
| `status`, `problemCategory` | Progress or outcome, and optional failure classification. These fields are absent for Events. |
| `attributes` | Event payload or details of the observed execution or call. Only this object is subject to `STANDARD` truncation. |

For an Event, `attributes` contains its payload and `entityMetadata` its ID and source relationships. For example, an output Event produced by an Action is recorded as:

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

`producerExecutionId` identifies the Action execution that produced the Event, `upstreamEventId` the preceding Event in its flow, and `upstreamActionName` the associated Action. A runtime-generated Event may have an upstream Event without an Action producer. Action records carry `triggerEventId`, linking the execution to the Event that triggered it. When an Action is not selected, its selected component calls still keep their `parentExecutionId`.

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

`entityMetadata.model` is the model or deployment configured on the Resource, not a provider-confirmed model identity. A successful LLM record includes `entityMetadata.usage` when the model integration returns token counts; each count is optional, zero is kept, and `usage` is omitted when neither count is available. Each retry reports its own usage, and a response replayed from durable state reports its usage again.

An Action execution or component call can have several records sharing its `executionId`, one per status such as `created`, `started`, `success`, `failed` or `reused`; correlate them by `executionId` and use timestamps rather than file order for latency. Records are best effort: one can be missing or repeated, and failover or replay can give a component call a new ID, so a missing record does not prove that a call never ran.

### Recording Level

| Level | Behavior |
|--------|----------|
| `OFF` | Write no matching record. |
| `STANDARD` | Truncate or summarize large values under `attributes` using the configured limits. |
| `VERBOSE` | Retain attributes without truncation, after media sanitization. |

`trace-log.standard.max-string-length`, `trace-log.standard.max-array-elements`, and `trace-log.standard.max-depth` control long strings, large arrays, and deep nesting respectively. Setting a threshold to `0` disables that truncation limit. Setting all three to `0` makes `STANDARD` retain the same content as `VERBOSE`, apart from the `level` label.

**Media sanitization.** Both `STANDARD` and `VERBOSE` omit inline Base64 media data from typed content blocks and remove userinfo, query strings, and fragments from media URLs. This includes blocks in `ChatMessage`, `ChatResult`, Tool results, and other Event attributes. Media metadata is retained. Message text is not automatically redacted.

**Fields preserved in full.** Truncation never changes entity identity, relationships, entity metadata, timestamps, statuses, or problem categories. It applies only under `attributes`, so `STANDARD` retains the relationships needed to correlate the records that were selected.

When content is truncated, its value is replaced with a wrapper:

| Truncated content | Replacement |
|-------------------|-------------|
| Long string | `{"truncatedString": "<first N chars>...", "omittedChars": M}` |
| Large array | `{"truncatedList": [<first N elements>], "omittedElements": M}` |
| Deep object | `{"truncatedObject": {<scalar fields only>}, "omittedFields": N}` |

A truncated field changes JSON type. Consumers requiring the full attribute schema should select that entity at `VERBOSE`. `traceLogTruncatedRecords` increments once per record with truncated attributes, regardless of the number of truncated fields.

### Trace Tree Reader

The `flink-agents-trace-tree` command is installed with the Python wheel. It rebuilds Event causal trees from Trace Log records. Pass a file, or a directory containing `traces-*.log` or `*.trace-log.log` files; both JSONL and pretty-printed JSON are supported.

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

The reader links Events through `entityMetadata.eventId`, `upstreamEventId` and `upstreamActionName`, so the default Event-only targets are enough; other records are skipped. It accepts `schemaVersion: 1` and skips records with a missing or unsupported version with a warning. An InputEvent with no upstream Event starts a tree. Shared descendants can appear under several branches, so the result may be a DAG rather than a strict tree, and sibling order does not indicate execution order. If the recording targets filtered out an upstream Event, that part of the tree cannot be rebuilt.

The JSON result exposes the same graph:

| Field | Meaning |
|-------|---------|
| `roots` | IDs of root InputEvents, one per reconstructed tree. |
| `nodes` | Event nodes indexed by Event ID. |
| `nodes[id].eventId`, `eventType`, `timestamp` | Event identity, routing type, and the first observed timestamp. The reader's `eventType` comes from the log record's `entityName`. |
| `nodes[id].observationCount` | Number of log observations with this Event ID, including any conflicting observations reported in `warnings`. |
| `nodes[id].upstreamEdges` | Distinct recorded upstream Event and Action references. Consult `warnings` for missing or invalid relationships. |
| `nodes[id].actions` | Actions linked from this Event; each has a `name` and a `children` list of output Event IDs. |
| `warnings` | Problems encountered while reading records or reconstructing relationships, such as malformed records, conflicting Event identities, missing upstream Events, edges without an Action name, and cycles. Cycle edges are removed before rendering. |
