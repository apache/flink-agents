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
| **Agent**   | eventLogTruncatedEvents                          | Number of event log records whose payload was truncated at `STANDARD` level. Increments once per event, regardless of how many fields inside it were truncated. Use this to decide whether to raise truncation thresholds or move specific event types to `VERBOSE`. | Count |
| **Agent**   | eventLogWriteFailures                           | Number of Event Log write attempts for which `append`, `flush`, or both failed. Event Log writes are best-effort and do not fail the job. | Count |

For a locally observed input run, `inputRunLatencyMs` is split into queueing and processing time at the input-run start boundary. `numOfPendingInputEvents` counts buffered inputs, while `numOfActiveInputRuns` counts logical runs; an asynchronous run remains active while it is waiting for its continuation.

An Action execution can be active while one of its continuation tasks is pending, so `numOfActiveActionExecutions` and `numOfPendingActionTasks` are independent. Action scheduling latency is recorded only for the initial task; continuation queueing does not create another scheduling sample.

Input-run outcomes and all latency samples are process-local. Runs or Action executions already in flight when a task is restored do not produce latency samples because their original timestamps are unavailable. An input Event restored from the pending queue can still produce an outcome and processing-latency sample after it starts in the new task attempt, but it does not produce queue or end-to-end latency. Current-count gauges are rebuilt from Flink state after restore.

#### Execution Metrics

Execution metrics are derived from LLM and Tool execution lifecycle events. The `model_resource`, `tool`, `skill`, and `mcp_server` scopes are independent key-value scopes directly under an Action; none is nested under another. The existing `model` scope remains dedicated to model usage metrics.

| Scope | Metrics | Description | Type |
|-------|---------|-------------|------|
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsSucceeded | The number of framework-observed model invocations that returned successfully. | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.numOfLlmCallsFailed | The number of framework-observed model invocations that failed. | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.llmCallLatencyMs | Latency of each framework-observed model invocation, excluding structured-output parsing and retry wait time. | Histogram |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryCount | The number of additional model invocations initiated when `ErrorHandlingStrategy.RETRY` is configured. Only recorded when at least one retry occurs. See [retry-wait-interval]({{< ref "docs/operations/configuration#core-options" >}}). | Count |
| **Model Resource** | action.\<action_name\>.model_resource.\<resource_name\>.retryWaitSec | The total backoff time, in seconds, accumulated when `ErrorHandlingStrategy.RETRY` is configured. Only recorded when at least one retry occurs. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsSucceeded | The number of successful calls to the Tool. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.numOfToolCallsFailed | The number of failed calls to the Tool. | Count |
| **Tool** | action.\<action_name\>.tool.\<tool_name\>.toolCallLatencyMs | Tool call latency. | Histogram |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.numOfSkillLoads | The number of terminal explicit `load_skill` calls attributed to the Skill, regardless of outcome. | Count |
| **Skill** | action.\<action_name\>.skill.\<skill_name\>.skillLoadLatencyMs | Latency of explicit `load_skill` calls. | Histogram |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsSucceeded | The number of successful Tool calls served by the MCP Server. | Count |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.numOfMcpToolCallsFailed | The number of failed Tool calls served by the MCP Server. | Count |
| **MCP Server** | action.\<action_name\>.mcp_server.\<server_name\>.mcpToolCallLatencyMs | Tool call latency aggregated across the MCP Server. | Histogram |

An LLM metric represents one framework invocation of `ChatModel`. A framework retry that calls the model again produces another LLM outcome and latency sample; retries hidden inside a provider or connection are not observed. Every named Tool execution emits Tool metrics. Skill metrics are emitted only for explicit `load_skill` calls; subsequent Tool calls are not inferred to belong to a Skill. MCP metrics aggregate only Tool executions carrying an explicit MCP Server resource name. A `load_skill` or MCP Tool execution therefore contributes to both its Tool scope and the corresponding Skill or MCP Server scope.

Execution metrics currently inherit Agent Trace's durable-replay behavior. During fine-grained recovery, a cached durable LLM or Tool result is reported as a new successful execution because child cache reuse is not exposed to execution reporting. The corresponding success counter therefore increments and the latency histogram may receive a near-zero sample even though the underlying model or Tool was not invoked. Distinguishing reused child executions is follow-up work.

Tool names that are not registered runtime resources are aggregated under the fixed `tool=unknown` scope. Requested Skill names that do not resolve in the runtime registry are similarly aggregated under `skill=unknown`. The original requested names remain available in Agent Trace records, while Metric scope cardinality remains bounded.

Tool outcomes follow the existing language-specific Tool contracts. In both Java and Python, resource preparation or invocation exceptions are failures and a normal return is successful. Java additionally treats an unsuccessful `ToolResponse` as a failed Tool execution. Python Tools return arbitrary values and currently have no equivalent explicit error-result type, so the runtime does not infer failure from a normally returned Python value.

`numOfSkillLoads` counts terminal calls rather than successful loads. Under the current Tool contracts, a `load_skill` not-found response returns normally and is therefore observed as a successful Tool outcome. Consequently, Tool and MCP outcome metrics use the same names and scopes in both runtimes, but explicit error-result semantics are not yet identical. This alignment, including explicit failure results for framework Tools such as `load_skill`, is tracked in [Issue #956](https://github.com/apache/flink-agents/issues/956).

Execution latency tracking is process-local. A latency sample is recorded only when the execution start and terminal events are observed in the same task attempt; LLM and Tool terminal counters are still updated when a restored execution has no local start timestamp.

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

## Event Log

The system supports two types of event loggers: **SLF4J Event Log** (default) and **File Event Log**.

By default, the SLF4J Event Log is used. If `baseLogDir` is configured, the system automatically switches to the File Event Log.

Event Log is an observability output. An `append` or `flush` failure does not fail Event processing or the Flink job. The first failure is logged at `WARN`, subsequent failures are logged at `DEBUG`, and every failed write attempt increments `eventLogWriteFailures`.

### SLF4J Event Log (Default)

The **SLF4J Event Log** outputs events through a dedicated SLF4J logger (`org.apache.flink.agents.EventLog`). On startup, the logger **automatically configures** log4j2 to write events to a separate file (`{log.file}.event-log.log`) in Flink's log directory, making them visible in Flink's Web UI **Logs** tab. **No manual log4j2 configuration is required.**

Because all subtasks on a TaskManager share the same log destination, each record additionally carries `jobId`, `taskName`, and `subtaskId` top-level fields so consumers can still distinguish events from different subtasks. The rest of the record follows the common [JSON Format](#json-format) described below.

### File Event Log

The **File Event Log** is a file-based event logging system that stores events in structured files within a flat directory. To use it, configure `baseLogDir` in your Flink `config.yaml`.

By default, each event is recorded in **JSON Lines (JSONL)** format, with one JSON object per line. When [`prettyPrint`]({{< ref "docs/operations/configuration#core-options" >}}) is enabled, each event is written as formatted multi-line JSON instead, and the log file is no longer in valid JSONL format.

#### File Structure

The log files follow a naming convention consistent with Flink's logging standards and are stored in a flat directory structure:

```
{baseLogDir}/
├── events-{jobId}-{taskName}-{subtaskId}.log
├── events-{jobId}-{taskName}-{subtaskId}.log
└── events-{jobId}-{taskName}-{subtaskId}.log
```

By default, all File-based Event Logs are stored in the `flink-agents` subdirectory under the system temporary directory (`java.io.tmpdir`). You can override the base log directory with the `agent.baseLogDir` setting in Flink `config.yaml`.

### JSON Format

The JSON record format described here applies to both the SLF4J and File event loggers. The SLF4J logger adds `jobId`, `taskName`, and `subtaskId` fields on top (see [SLF4J Event Log](#slf4j-event-log-default)); the File logger encodes those values in the file path instead.

Each record is a flat JSON object. Framework-owned field names use camelCase, consistent with the existing Event Log format. A record contains the event occurrence time, the resolved `logLevel`, optional execution trace fields, the event identity, and the event payload in `eventAttributes`. The flat `eventType` field makes it easy for downstream tools (e.g. `grep`, `jq`, log shippers) to filter by event type without parsing nested JSON.

Agent Trace persistence is disabled by default. Set `event-log.trace.enabled: true` to add trace context to business Event records and persist Action, LLM, Parser, and Tool lifecycle Events. When Trace persistence is disabled, business Events continue to be logged without the trace fields shown below.

After fine-grained recovery, a cached durable LLM or Tool result is currently recorded as a new successful execution because cache reuse is not exposed to execution reporting. Distinguishing reused child executions is follow-up work.

Example Trace record:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "logLevel": "STANDARD",
  "inputRunId": "7f1b5d20-86c6-4a5d-b65a-9c41757f2e11",
  "businessKey": "order-1001",
  "agentName": "ReActAgent",
  "executionId": "9cb3c3df-1d3b-4c45-ae7d-1b28a1b86522",
  "parentExecutionId": "55cc59a8-f4e8-4f15-badf-4833f8a8a97a",
  "entityType": "llm",
  "entityName": "_default_chat_model",
  "entityMetadata": {"model": "qwen-max"},
  "eventId": "80f30736-2759-41d8-aa59-9c8ad481ab42",
  "eventType": "_execution_finished_event",
  "status": "success",
  "eventAttributes": {}
}
```

For an LLM execution, `entityName` identifies the configured ChatModel Resource, while
`entityMetadata.model` identifies the model or deployment configured on that Resource. This value
is the requested identifier and is not a provider-confirmed model identity.

`upstreamEventId` identifies the Event consumed by the Action that emitted the current Event, and `upstreamActionName` identifies that Action. Both are top-level Event Log fields derived from framework-managed Event lineage and remain outside `eventAttributes`. A root `InputEvent` omits both fields.

### Trace Tree Reconstruction

The `flink-agents-trace-tree` command is installed with the Flink Agents Python wheel. It rebuilds InputEvent-rooted Trace Trees from business Events in a saved File Event Log and ignores execution lifecycle Events. The four lifecycle types `_execution_started_event`, `_execution_finished_event`, `_execution_failed_event`, and `_execution_reused_event` are reserved for the framework. A record is ignored only when its type, status, and execution identity match the corresponding framework lifecycle shape. A business Event that uses a reserved type without that shape is retained and reported with a reconstruction warning. The reader accepts both the current flat record shape and the previous nested `event` shape, including files that contain both formats. Pass either one log file for text output or a log directory for Trace Tree JSON:

```bash
flink-agents-trace-tree /path/to/events-job-task-0.log
flink-agents-trace-tree /path/to/event-log-directory --format json
```

For example, the text output for an `InputEvent -> MiddleEvent -> OutputEvent` lineage is:

```text
Trace Tree 1
  _input_event (dad5ed00-80e2-4746-8bdb-f126bad504b5)
    [Action: action1]
      MiddleEvent (39361629-4f1d-4b62-b734-a48b181cb6e0)
        [Action: action2]
          _output_event (f452ce08-c672-4c9c-841f-d81d15a900c5)
```

In JSON output:

- `roots` lists the Event IDs of the root `InputEvent`s, one per reconstructed Trace Tree.
- `nodes` maps each logical Event ID to one Event node with the following fields:
  - `eventId`: the logical Event ID of the node.
  - `eventType`: the top-level `eventType` routing key from the Event Log record.
  - `timestamp`: the `timestamp` of the first Event Log record observed for this Event ID.
  - `observationCount`: Number of times this Event was recorded in the Event Log. Values greater
    than 1 usually mean the Event was reused during replay.
  - `upstreamEdges`: the distinct recorded causal edges retained on this Event, each with an
    `upstreamEventId` and an `upstreamActionName`. Repeated observations of the same
    `(Event ID, upstream Event ID, upstream Action name)` edge are deduplicated. An edge that would
    close a cycle is omitted. An invalid edge may remain for diagnosis even when a reconstruction
    warning prevents it from being added to the parent Event's `actions`.
  - `actions`: the virtual Action nodes triggered by this Event, each with the Action `name` and
    the `children` Event IDs it emitted.
- `warnings` contains the reconstruction warnings described below.

Distinct edges are retained, so reused Events can be shared by multiple InputEvent-rooted
branches and the combined result is a DAG rather than a strict tree. For example, two roots that
share one reused output Event through the same Action produce:

```json
{
  "roots": ["root-1", "root-2"],
  "nodes": {
    "root-1": {
      "eventId": "root-1",
      "eventType": "_input_event",
      "timestamp": "2024-01-15T10:30:00Z",
      "observationCount": 1,
      "upstreamEdges": [],
      "actions": [
        {"name": "action1", "children": ["shared-1"]}
      ]
    },
    "root-2": {
      "eventId": "root-2",
      "eventType": "_input_event",
      "timestamp": "2024-01-15T10:31:00Z",
      "observationCount": 1,
      "upstreamEdges": [],
      "actions": [
        {"name": "action1", "children": ["shared-1"]}
      ]
    },
    "shared-1": {
      "eventId": "shared-1",
      "eventType": "_output_event",
      "timestamp": "2024-01-15T10:30:01Z",
      "observationCount": 2,
      "upstreamEdges": [
        {"upstreamEventId": "root-1", "upstreamActionName": "action1"},
        {"upstreamEventId": "root-2", "upstreamActionName": "action1"}
      ],
      "actions": []
    }
  },
  "warnings": []
}
```

{{< hint info >}}
During ActionStateStore recovery, a reused output Event keeps its Event ID when a completed
Action result is replayed, while its lineage is rebound to the recovered execution's current
triggering Event. The same logical Event ID may therefore appear in multiple physical Event Log
records with distinct upstream edges, as in the example above. Reusing an Event ID with the same
Event type and content is valid.
{{< /hint >}}

When observations with the same Event ID disagree on Event type or content, only observations
matching the first add distinct entries to `upstreamEdges`, while all observations count toward
`observationCount`. For conflicting observations, the reader emits one `EVENT_ID_CONFLICT`
warning per distinct (`upstreamEventId`, `upstreamActionName`) pair and includes each field when
present. The resulting canonical node and its descendants remain in the reconstructed trace.

The reader derives virtual Action nodes from `upstreamActionName`; they are not separate Event Log
records. Log order does not add execution-order semantics, but it controls display order and
first-observation fields such as `timestamp`. For a fixed set of reconstructed nodes and edges,
cycle detection uses Event IDs and Action names in a stable order, so reordering non-conflicting
records does not change which cycle-closing edge is omitted. For `EVENT_ID_CONFLICT`, the first
observation determines the canonical Event type and content, so the order of conflicting
observations remains significant.

Reconstruction warnings are written to standard error and included in JSON output while valid
InputEvent-rooted branches are retained. The reader keeps valid records from the same input and
continues with other Event Log files.

A `CYCLE_DETECTED` warning identifies an omitted edge with `eventId` for the child,
`upstreamEventId` for the parent, and `upstreamActionName` for the Action. The edge is omitted from
both the parent's `actions` and the child's `upstreamEdges`, so cycle pruning is represented
consistently in both directions.

| Warning Code           | Trigger Condition                                                          |
|------------------------|----------------------------------------------------------------------------|
| `EVENT_ID_CONFLICT`    | Records carrying the same Event ID disagree on the Event type or content.  |
| `INVALID_ROOT_LINEAGE` | An InputEvent carries upstream lineage, which a root Event must not have.  |
| `UNLINKED_EVENT`       | A non-InputEvent has no upstream Event.                                    |
| `MISSING_ACTION_NAME`  | An Event has an upstream Event but no upstream Action name.                |
| `MISSING_PARENT`       | An Event references an upstream Event with no valid node in the Event Log. |
| `CYCLE_DETECTED`       | A lineage edge closes a cycle and is omitted from the reconstructed DAG.   |
| `MALFORMED_RECORD`     | An Event Log record is invalid or partially written.                       |
| `RESERVED_EVENT_TYPE`  | A business Event uses a framework-reserved lifecycle Event type.           |
| `UNREADABLE_FILE`      | An Event Log file could not be read.                                       |

### Event Log Levels

Each event type is logged at a configurable verbosity. Three levels are supported:

| Level      | Behavior                                                                                                       |
|------------|----------------------------------------------------------------------------------------------------------------|
| `OFF`      | Events of this type are not logged.                                                                            |
| `STANDARD` | Events are logged, but the payload may be truncated or summarized to keep logs concise. **This is the default.** |
| `VERBOSE`  | Events are logged with the full, untruncated payload.                                                          |

The global default is set by [`event-log.level`]({{< ref "docs/operations/configuration#core-options" >}}). At `STANDARD` level, the payload is shrunk along three independent axes — long strings, large arrays, and deep nesting — controlled by `event-log.standard.max-string-length`, `event-log.standard.max-array-elements`, and `event-log.standard.max-depth` respectively. Setting any threshold to `0` disables that specific truncation; setting all three to `0` makes `STANDARD` behave identically to `VERBOSE` (apart from the `logLevel` label). The exact truncation strategy may evolve over time; the contract is only that `STANDARD` keeps logs concise while `VERBOSE` preserves the full payload.

**Fields that are never truncated.** Structural and identifying fields are always preserved in full so log consumers can still group, route, and correlate records: `timestamp`, `logLevel`, trace fields such as `inputRunId` and `executionId`, `eventId`, `eventType`, and lifecycle fields such as `status` and `problemCategory`. Truncation only applies to large nested content under `eventAttributes` (long strings, big arrays, deeply nested objects).

**Truncation wrapper format.** When a field is truncated at `STANDARD` level it is replaced by a JSON object that records what was retained and what was dropped. This keeps the record valid JSON and lets downstream tooling detect truncation programmatically:

| Truncated content | Replacement                                                            |
|-------------------|------------------------------------------------------------------------|
| Long string       | `{"truncatedString": "<first N chars>...", "omittedChars": M}`         |
| Large array       | `{"truncatedList": [<first N elements>], "omittedElements": M}`        |
| Deeply nested object | `{"truncatedObject": {<scalar fields only>}, "omittedFields": N}`   |

A truncated field changes JSON type (e.g. a `string` field becomes an `object`). Consumers that need a stable schema should switch the affected event type to `VERBOSE`. A counter metric `eventLogTruncatedEvents` (see [Event and Action Metrics](#event-and-action-metrics)) records how often truncation kicked in — use it to decide whether to raise the thresholds or move noisy event types to `VERBOSE`.

Example record at `STANDARD` with a long string and a large array truncated:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "logLevel": "STANDARD",
  "eventId": "...",
  "eventType": "_chat_request_event",
  "eventAttributes": {
    "model": "gpt-4",
    "messages": {
      "truncatedList": [
        {"role": "system", "content": "You are a helpful assistant..."},
        {"role": "user", "content": {"truncatedString": "Analyze this doc...", "omittedChars": 1000}}
      ],
      "omittedElements": 30
    }
  }
}
```

### Per-event-type log levels

You can override the level for individual event types using the `event-log.type.<EVENT_TYPE>.level` config key, where `<EVENT_TYPE>` is the event's routing type string (the same string that appears as `eventType` in the JSON log). Although the field name uses camelCase, built-in Event type values remain snake-cased:

| Event                    | `<EVENT_TYPE>` value             |
|--------------------------|----------------------------------|
| `InputEvent`             | `_input_event`                   |
| `OutputEvent`            | `_output_event`                  |
| `ChatRequestEvent`       | `_chat_request_event`            |
| `ChatResponseEvent`      | `_chat_response_event`           |
| `ToolRequestEvent`       | `_tool_request_event`            |
| `ToolResponseEvent`      | `_tool_response_event`           |
| `ContextRetrievalRequestEvent`  | `_context_retrieval_request_event`  |
| `ContextRetrievalResponseEvent` | `_context_retrieval_response_event` |
| Execution lifecycle: started    | `_execution_started_event`          |
| Execution lifecycle: finished   | `_execution_finished_event`         |
| Execution lifecycle: failed     | `_execution_failed_event`           |
| Execution lifecycle: reused     | `_execution_reused_event`           |

Each event type has its own independently overridable key, so a job-level override does not clobber other entries from `config.yaml`.

`event-log.trace.enabled` controls whether execution lifecycle Events are produced. When Trace recording is enabled, these Events still use the per-event-type level resolution above. Setting any `_execution_*` type to `OFF` suppresses that lifecycle Event and may make the recorded Trace incomplete.

Resolution is hierarchical — the resolver walks up dot-separated segments of the event type, mirroring Log4j's logger hierarchy. For a user-defined event type `com.example.myapp.OrderEvent`, the lookup order is:

1. `event-log.type.com.example.myapp.OrderEvent.level` (exact match)
2. `event-log.type.com.example.myapp.level` (package prefix)
3. `event-log.type.com.example.level`
4. … (continues walking up)
5. `event-log.level` (global default)
6. Built-in default: `STANDARD`

Built-in event type strings like `_chat_request_event` contain no dots, so they are matched exactly against the configured key.

Example `config.yaml`:

```yaml
# Keep all events at STANDARD by default
event-log.level: STANDARD

# Log every ChatRequestEvent with the full payload
event-log.type._chat_request_event.level: VERBOSE

# Skip context retrieval requests entirely
event-log.type._context_retrieval_request_event.level: OFF
```

Because each event type has its own config key, you can override a single level at job submission time without touching the shared `config.yaml`:

```bash
flink run ... \
  -Devent-log.type._chat_request_event.level=VERBOSE
```

Other per-type levels from `config.yaml` are preserved — the `-D` flag only overrides the one key it names.

### Compatibility Notes

- **Default behavior changed.** Before this feature, every event was logged in full. The new default is `STANDARD`, which truncates large payloads. To restore the previous behavior either globally or per type, set the level to `VERBOSE`.
- **Old log records still parse.** Records written in the previous nested format (`eventType` plus `event`) continue to deserialize. They do not contain run or execution context and therefore cannot reconstruct a complete Agent Trace.
- **External consumers must migrate to the flat record shape.** The nested `event` object is removed: `event.id` becomes the top-level `eventId`, and `event.attributes` becomes the top-level `eventAttributes`. `eventType` was already available at the top level and remains there. Compatibility in the framework deserializer does not automatically update existing `jq` expressions, log-shipper mappings, or downstream queries that read the previous nested JSON shape directly.
- **Python Event IDs now identify occurrences.** Python previously generated a deterministic UUID from Event content and regenerated it when the content changed. It now assigns a UUID4 to each Event occurrence, matching the identity semantics used by Agent Trace. Consumers must not rely on equal Event payloads producing equal IDs for deduplication.
- **Existing pending ActionTask state is not compatible with the new schema.** Agent Trace adds execution identity and Action lifecycle state to pending `ActionTask` state. Restoring savepoints containing that state from before this change would require a versioned `ActionTask` state serializer, which is not included in this feature.
- **Event Log write failures are now best-effort.** Earlier versions propagated `append` or `flush` failures into Event processing. Write failures now leave the job running and are reported through the `eventLogWriteFailures` metric and a first-failure `WARN` log.
