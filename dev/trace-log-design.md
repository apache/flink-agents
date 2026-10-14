This proposal builds on [Recording Agent Traces in the Event Log (#900)](https://github.com/apache/flink-agents/discussions/900). It brings Event and execution logging into a unified Trace Log model while preserving the observation coverage and execution semantics of the existing design.

## 1. Background

Event Log currently serves two purposes: recording Events that flow between Actions and recording the execution lifecycle of Actions and their LLM, Parser, and Tool calls. Lifecycle reports are represented as synthetic Events, such as `_execution_finished_event`, even though they are used only for observability and are never routed to Actions.

Using Event for both purposes introduces ambiguity into the model and its configuration:

- **Event has two meanings.** The same term and data structure describe both objects in the programming model and records used to observe execution.
- **Logging configuration spans both models.** Event Log levels and per-type settings control ordinary Event logging, while execution logging requires an additional Trace switch. Selecting execution reports can also require users to know synthetic Event types such as `_execution_finished_event`.
- **Execution reports carry redundant metadata.** An execution already has an `executionId` and a `status`, but each lifecycle report also receives an Event UUID and type because it is represented as an Event.

We propose **replacing Event Log with a unified Trace Log**. Event will retain its meaning in the programming model: an object that Actions consume or emit. Trace Log will describe Event flow and execution through a common record format and a single set of logging controls, preserving the information currently available in Event Log.

## 2. Goals and Scope

### Goals

1. **Give Event and Trace distinct responsibilities.** Event belongs to the programming model; Trace provides a common representation for observations of Events and execution.
2. **Preserve existing observation coverage.** This includes Event content, execution status, Memory reads and writes, initial Memory snapshots, and the relationships between Events and the executions that produce or consume them.
3. **Unify logging configuration.** Users should be able to choose which records to write, how much content to retain, and where to send the output, with local overrides for specific Event types, Actions, or component calls.

### Scope

- The design covers four areas: the data model, runtime collection, configuration, and log consumption.
- Event routing, Action execution, and EventListener callbacks retain their existing behavior, as do recovery and result reuse within the same version.
- Trace remains best effort. Records may be missing or duplicated; complete execution histories, exactly-once logging, and deduplication after recovery are outside the scope of this proposal.
- Compatibility with previous Event JSON and historical Event Log formats is outside scope. Applications, log consumers, and external data must migrate to the new contracts.
- Migration of persisted runtime state across versions is also outside scope. Upgrade guidance must identify affected checkpoint and savepoint restore paths, as well as reuse of results persisted in ActionStateStore.

## 3. Proposed Design

### 3.1 Data Model and Contract

#### Record composition

TraceRecord replaces EventLogRecord as the unit written to the log. It contains a TraceContext, a timestamp, and attributes, with execution status and failure category where applicable. The timestamp records observation time by default; component reports may supply the occurrence time. Event observations and execution reports use this same record type.

The following comparison shows the object structures for the same failed execution. Nesting indicates field ownership; the next section illustrates the serialized JSON format.

```text
Current: EventLogRecord                         Proposed: TraceRecord
├── eventContext: EventContext                  ├── context: TraceContext
│   ├── timestamp                               │   ├── inputRunId, businessKey, agentName
│   └── eventType = "_execution_failed_event"   │   ├── entityType, entityName
├── traceContext: ExecutionTraceContext         │   ├── executionId, parentExecutionId
│   ├── inputRunId, businessKey, agentName      │   └── entityMetadata
│   ├── entityType, entityName                  ├── timestamp
│   ├── executionId, parentExecutionId          ├── status = "failed"
│   └── entityMetadata                          ├── problemCategory (when supplied)
└── event: Event                                └── attributes
    ├── id (generated UUID)                         ├── errorType
    ├── type = "_execution_failed_event"            └── errorMessage
    ├── upstreamEventId = null
    ├── upstreamActionName = null
    └── attributes
        ├── status = "failed"
        ├── problemCategory (when supplied)
        ├── errorType
        └── errorMessage
```

Execution identity remains in the context. The synthetic Event, its generated UUID, and its lifecycle type are removed. Status and failure category become fields on TraceRecord, while error details remain in `attributes`.

- **TraceContext** retains the field structure of `ExecutionTraceContext`. `inputRunId`, `businessKey`, and `agentName` retain their existing meanings. The changes extend the context to describe Events:
  - For Event records, `entityType` is `event` and `entityName` is the Event's type.
  - `executionId` and `parentExecutionId` retain their execution semantics and are absent from Event records. An Event record refers to its producer through `entityMetadata.producerExecutionId`.
  - `entityMetadata` holds Event identity and source information (`eventId`, `producerExecutionId`, `upstreamEventId`, and `upstreamActionName`) on Event records. Action records gain `triggerEventId` to identify the Event that triggered the execution.
- **TraceRecord** contains its TraceContext. Serialization places context fields such as `entityType` and `inputRunId` at the top level of the JSON record.
- **EventContext** remains part of the EventListener callback contract and is fully independent of TraceContext. There is no containment, inheritance, or conversion relationship between the two, and TraceRecord construction does not depend on EventContext.

Payload truncation applies only to `attributes`. IDs, relationship fields, and the top-level `problemCategory` remain intact, preserving the information needed to connect records and classify failures. Truncation affects only the serialized content; the Event delivered to user code is unchanged.

#### Representing an Event

An Event observation is a standalone TraceRecord. Its context identifies the Event, its attributes contain the Event payload, and its metadata links it to the producing Action execution when one exists.

Consider a `create_order` Action execution (`action-1`) that consumes Event `event-1` and emits an `OrderCreated` Event (`event-2`). The examples below use abbreviated IDs and omit timestamps and common metadata for brevity.

**Previous Event Log record, with tracing enabled.** The record combines the output Event with its producer's execution context: `entityType`, `entityName`, and `executionId` describe `create_order`, while the `event*` fields describe `OrderCreated`.

```json
{
  "inputRunId": "run-1",
  "entityType": "action",
  "entityName": "create_order",
  "executionId": "action-1",
  "eventId": "event-2",
  "eventType": "OrderCreated",
  "upstreamEventId": "event-1",
  "upstreamActionName": "create_order",
  "eventAttributes": {
    "orderId": "order-1"
  }
}
```

**Proposed TraceRecord.** The context now describes `OrderCreated` itself. Its relationship to the `create_order` execution is explicit in `producerExecutionId`.

```json
{
  "schemaVersion": 1,
  "inputRunId": "run-1",
  "entityType": "event",
  "entityName": "OrderCreated",
  "entityMetadata": {
    "eventId": "event-2",
    "producerExecutionId": "action-1",
    "upstreamEventId": "event-1",
    "upstreamActionName": "create_order"
  },
  "attributes": {
    "orderId": "order-1"
  }
}
```

Every TraceRecord JSON includes a top-level integer `schemaVersion`, initially `1`, identifying the log format independently of Flink Agents releases and persisted runtime state. Incompatible field or semantic changes require a new version; optional fields can be added within the same version. Readers ignore unused fields. The built-in reader warns and skips records with a missing, invalid, or unsupported version while continuing to read other records.

The Event's `type` maps to `entityName`, its `id` to `entityMetadata.eventId`, and its payload directly to `attributes`. This preserves the Event's identity and content without embedding the Event object in the record.

Event flow and execution relationships use the following fields:

| Relationship | Representation |
|---|---|
| An Action invokes an LLM, Parser, or Tool | The child execution's `parentExecutionId` points to the Action execution |
| An execution emits or replays an Event | The Event record's `entityMetadata.producerExecutionId` identifies that execution |
| An Event triggers an Action execution | The Action record's `entityMetadata.triggerEventId` identifies that Event |
| `create_order` consumes `event-1` and emits `event-2` | The `event-2` record's `entityMetadata.upstreamEventId` is `event-1`, and its `upstreamActionName` is `create_order` |

These relationships preserve a distinction between an Event's identity and the execution that produces or replays it:

- An Event has no execution lifecycle. Its record therefore omits `executionId`, `parentExecutionId`, and top-level `status` and `problemCategory`. User attributes with these names remain in `attributes` and are subject to payload truncation.
- `producerExecutionId` is absent when there is no producing Action execution, as with a root InputEvent. Framework-generated Events retain their existing source information even when no Action execution can be referenced.
- `eventId` identifies the Event, not a unique observation. The same Event may appear in multiple records, including when a saved output is replayed during recovery.
- On replay, `producerExecutionId` identifies the execution emitting the saved output at that point. This may differ from the execution that originally produced it.
- Action `executionId` retains its existing task creation and restoration semantics. A restart does not necessarily assign a new Action execution ID.
- For a new component invocation or retry attempt, the `created` and `started` reporting helpers, including timestamped starts, can allocate and return an `executionId`. The caller retains and explicitly reuses that ID in subsequent lifecycle reports, including duplicate reports. Overlapping calls with identical type, name, and metadata receive different IDs. Metadata may evolve across reports and is not a correlation key.
- Successful LLM reports add per-response `usage` using the ChatMessage's existing `promptTokens` and `completionTokens` field names and values. Available zero counts are retained and missing counts are omitted. Each retry or cached-response replay reports that response's usage, independently of metric collection. The `model` field remains the configured model or deployment.
- Ordinary asynchronous waits retain the component call's ID. Failover or replay may create new component call IDs. Stable correlation within a call does not guarantee a terminal observation, exactly-once output, or deduplication after recovery.

Source fields move from the programming-model Event to Trace metadata. Custom Event construction and reconstruction continue to use the Event's ID, type, and attributes.

### 3.2 Collection and Runtime Flow

Integrating TraceRecord into the runtime requires three changes: constructing records directly at the existing collection points, moving Event source information into those records, and retaining the context needed to connect records independently of logging configuration.

#### Previous Event Log flow

In the previous Event Log flow, records reached EventLogWriter through three paths:

- **Events:** EventRouter supplies the Event, EventContext, and optional ExecutionTraceContext before Action matching or downstream delivery. This path covers input, output, custom, and framework-generated Events, including Events with no consumers.
- **Action lifecycle:** ActionExecutionOperator reports start, completion, failure, and result reuse by creating lifecycle Events and passing them through ExecutionEventLogger.
- **Component calls:** Existing LLM, Parser, and Tool call sites report through ExecutionReporter and RunnerContext, which supply execution context and create lifecycle Events. Python reports use the existing Python-to-Java bridge.

#### Runtime changes

##### 1. Construct TraceRecords at the collection points

Each collection point will produce a TraceRecord describing the Event or execution it observes. Execution reports no longer need a synthetic Event to carry their status and content.

| Observation | Current construction | Proposed construction |
|---|---|---|
| Event | EventLogRecord combines the Event, EventContext, and optional execution context. | EventRouter constructs a TraceRecord with the Event's identity, content, and available run and source references. |
| Action or component execution | Reporting code creates a lifecycle Event and combines it with execution context. | Reporting code constructs a TraceRecord with execution context, status, and any failure details. |

All records then enter a common filtering, serialization, and output path governed by Trace Log configuration.

##### 2. Populate Event relationships from runtime context

In the previous Event Log model, the runtime wrote `upstreamEventId` and `upstreamActionName` onto an emitted Event and supplied its producer's execution context to the logger. Under the new model, the runtime places these relationships in Trace metadata:

- When creating an Action task, it records the triggering Event's ID in the Action's TraceContext as `entityMetadata.triggerEventId`.
- When constructing a TraceRecord for an Event emitted by that Action, it reads the triggering Event ID, Action name, and execution ID from the current task. These become `upstreamEventId`, `upstreamActionName`, and `producerExecutionId` in the Event record's `entityMetadata`.
- Component reports use the caller-supplied execution ID to construct child execution contexts, retaining their parent execution references. Correlation does not infer a call's identity from its type, name, or metadata; the existing JSON fields carry these identities and relationships.

##### 3. Preserve context independently of record filtering

Omitting an execution record must not remove the context needed to describe its output Events. For example, an `OrderCreated` record still references the `create_order` execution through `producerExecutionId` even when that Action's execution records are not written.

The runtime therefore retains the required TraceContext through asynchronous resumption and result replay, regardless of which records the logger selects. A replayed Event record references the execution replaying it, following the identity semantics described in Section 3.1.

#### Preserved behavior

- **Collection timing and coverage:** Events are observed before Action matching or downstream delivery. Memory observations are collected during an Action and emitted as Events when it completes; the optional run-begin Event captures initial short-term Memory before the input's Actions execute. Framework Event generation conditions and payloads are unchanged.
- **Routing and callbacks:** Event routing and EventListener delivery retain their existing behavior. Listeners continue to receive EventContext and Event through a separate callback path. Execution reports do not enter Action routing or trigger EventListener callbacks.
- **Execution and recovery:** Action execution and component invocation retain their existing behavior, as do recovery and result reuse within the same version. Saved output Events continue to re-enter EventRouter after the Action reuse report.

Trace reporting and output remain best effort and do not change Action execution or recovery guarantees.

### 3.3 Configuration

All logging options live under `trace-log.*`. `trace-log.targets` combines record selection and recording level in a list of targets. Each target pairs a required `scope` with an optional `level`; preset and entity targets use the same structure.

#### Target configuration

`scope` accepts either a preset or an entity selector:

- `EVENT_ONLY` selects Events.
- `ALL` selects all observed entities.
- An object with one category key (`event`, `action`, `tool`, `llm`, `parser`, or `subagent`) selects named entities of that type, for example `{event: OrderCreated}`. A value of `"*"` selects the whole type; `{event: "*"}` and `EVENT_ONLY` are equivalent.
- The generic object with required `entityType` and optional `entityName` also supports custom entity types reported through `ExecutionReporter`. Omitting `entityName` selects the whole type. A selector cannot combine category keys or mix a category key with the generic fields.

Java and Python expose immutable `TraceLogTarget` values through the existing `TRACE_LOG_TARGETS` option. Both APIs provide `all()`; whole-type factories `events()`, `actions()`, `tools()`, `llms()`, `parsers()`, and `subagents()`; and the corresponding singular factories accepting an entity name. Each factory accepts an optional `TraceLogLevel`. `entity(type, name, level)` supports arbitrary entity types; the name and level are optional. An omitted level remains unspecified until resolution. Typed targets and YAML are normalized to the same preset or generic selector representation and share validation, matching, inheritance, and duplicate handling.

`level` accepts `OFF`, `STANDARD`, or `VERBOSE`. `OFF` suppresses the entire matching record; it is not a scope and does not produce a record with empty attributes. `STANDARD` applies the configured attribute limits. `VERBOSE` skips STANDARD truncation. Both recording settings retain identities, relationship fields, entity metadata, timestamps, statuses, and problem categories in full. Existing typed `ChatMessage` media sanitization applies at both settings.

When `targets` is omitted, the default is `[{scope: EVENT_ONLY, level: STANDARD}]`. An explicit list replaces this default: it does not implicitly retain Event logging. An empty list records nothing. A list containing only a Tool scope therefore records only matching Tools.

The following configuration retains Event logging, suppresses `DebugEvent`, and records the `create_order` Action at VERBOSE level:

```yaml
agent:
  trace-log:
    targets:
      - scope: EVENT_ONLY
        level: STANDARD
      - scope: {event: DebugEvent}
        level: "OFF"
      - scope: {action: create_order}
        level: VERBOSE
    standard:
      max-string-length: 2000
      max-array-elements: 20
      max-depth: 5
    output-type: slf4j
    pretty-print: false
```

The `agent` section is the YAML configuration entry point; Java and Python configuration APIs use the corresponding `trace-log.*` keys. In YAML, write `level: "OFF"` with quotes to preserve its string value.

| Record | Effective behavior |
|---|---|
| `DebugEvent` | Omitted by its local OFF level. |
| Other Events | Written at STANDARD, following the EVENT_ONLY preset. |
| The `create_order` Action | Its lifecycle observations are written at VERBOSE. |
| Other Actions and component calls, including calls inside `create_order` | Omitted unless another target selects them. Execution itself is unaffected. |

Each target applies to the record's own entity type and name. Selecting an Action does not automatically select its output Events or child calls. Event-only logs show flows through Actions that emit Events; an Action that emits no Event requires its own target to record its lifecycle.

#### Name matching and level inheritance

Entity types and names are case-sensitive strings. Names match exactly unless they end in `.*`: `com.foo.*` matches `com.foo` and names beginning with `com.foo.`, but not `com.foobar.OrderCreated`. A category selector with `"*"`, or a generic selector without `entityName`, selects the whole entity type. Other wildcard forms are unsupported. Matching by Agent name, business key, status, or problem category remains outside scope.

An omitted level inherits from the next less specific matching scope. Resolution checks the exact name, matching namespace prefixes from longest to shortest, the entity type, and finally ALL. EVENT_ONLY is the Event type scope; it never supplies a level to other entity types. The first explicit level applies. If at least one scope matches but none specifies a level, the record uses STANDARD.

For example, a Tool type target at VERBOSE supplies the level to matching namespace and exact-name targets that omit it. An explicit OFF stops inheritance and suppresses recording; a more specific target must explicitly specify STANDARD or VERBOSE to enable recording again. An exact-name target without a level therefore inherits OFF from a matching namespace target even when the Tool type target specifies VERBOSE.

#### Selection and precedence

For each record, matching scopes are considered in this order to find an explicit level:

1. Exact entity type and name.
2. Namespace prefix within the entity type, with the longest prefix winning.
3. The whole entity type, including EVENT_ONLY for Events.
4. ALL.

A more specific target can disable recording under an enabled scope or enable recording under a scope with OFF level by specifying an explicit level. When no target matches, the record is omitted. Target order does not affect precedence. Duplicate scopes merge an omitted level with an explicit level and accept identical explicit levels. Different explicit levels for the same scope are rejected at startup, including equivalent scopes expressed through different selector forms.

Recording targets are parsed and validated at startup, and the effective targets are reported. These semantics are consistent across Java, Python, and YAML.

#### Output settings and attribute limits

Output settings sit directly under `trace-log`: `output-type`, `base-dir`, and `pretty-print`. `output-type` defaults to SLF4J. A non-empty `base-dir` selects FILE and takes precedence over `output-type`. When FILE is selected and `base-dir` is omitted, the directory is `java.io.tmpdir/flink-agents`.

The `standard` group contains `max-string-length`, `max-array-elements`, and `max-depth`, defaulting to 2000, 20, and 5. These limits apply only to attributes at STANDARD level. A limit of 0 removes that particular limit without disabling logging.

Output remains JSONL by default. `pretty-print: true` writes multiline JSON objects. Memory Event and run-begin Event options continue to control Event generation independently of record selection; `event-listeners` is unchanged.

#### Configuration migration

Legacy logging keys are rejected at startup, including when mixed with new keys. They require explicit migration; no compatibility mapping is provided.

| Existing option | New setting |
|---|---|
| `event-log.trace.enabled` | An EVENT_ONLY or ALL preset in `trace-log.targets` |
| `event-log.level` | The preset target's `level` |
| `event-log.type.<EVENT_TYPE>.level` | An entity target with `scope: {event: ...}` and `level` |
| `event-log.standard.max-string-length` | `trace-log.standard.max-string-length` |
| `event-log.standard.max-array-elements` | `trace-log.standard.max-array-elements` |
| `event-log.standard.max-depth` | `trace-log.standard.max-depth` |
| `eventLoggerType` | `trace-log.output-type` |
| `baseLogDir` | `trace-log.base-dir` |
| `prettyPrint` | `trace-log.pretty-print` |

- Migrate `event-log.trace.enabled: false` to an EVENT_ONLY preset, or `true` to an ALL preset, and put the previous global level in that preset's level. Migrate limits and output settings at the same time.
- Migrate ordinary Event-type settings to entity targets. Use an explicit `.*` namespace selector where the old configuration relied on dot-separated hierarchy fallback.
- Synthetic lifecycle filters have no general equivalent. A setting that suppressed only `_execution_finished_event` cannot be reproduced by an Action or Tool target, which applies to all lifecycle statuses for that entity. Status matching remains deferred.

Rejecting legacy keys prevents a configuration that previously disabled logging from silently falling back to new defaults and emitting records.

### 3.4 Output and Consumption

Both built-in output destinations serialize the same TraceRecord fields and add the resolved `level` (STANDARD or VERBOSE) to the JSON. `level` is output metadata, not a field on TraceRecord; OFF records are omitted. SLF4J also includes `jobId`, `taskName`, and `subtaskId` in each record, while file output identifies them in the file path. Trace records are emitted through SLF4J at INFO, independently of recording level.

#### Field mappings for readers and queries

Queries and parsers must adopt the new field mappings:

| Information | Previous representation | New representation |
|---|---|---|
| Event identity and content | `eventType`, `eventId`, and `eventAttributes` | For `entityType = "event"`, the type is `entityName`, the ID is `entityMetadata.eventId`, and the content is `attributes` |
| An output Event's source | Top-level `upstreamEventId` and `upstreamActionName` | The same fields in `entityMetadata` |
| Execution progress | Execution fields, synthetic lifecycle Event types, and status | `entityType`, `entityName`, and `executionId` describe the execution; `status` describes its lifecycle state |
| Recording level | Output-layer `logLevel` | Output-layer `level` |

#### Built-in Trace Tree support

The Trace Tree tool reads the new TraceRecord format and builds an Event–Action graph. Execution records remain outside graph construction; this proposal does not add execution-state or component-call visualization.

- The reader supports only TraceRecord. Historical flat Event Log records and records with a nested `event` object are unsupported; no historical-format conversion is included.
- Event records are selected by `entityType = "event"`. An Event's name does not make it an execution observation.
- The graph's output structure remains unchanged.
- Observations with the same Event ID and type represent the same Event. Attribute differences, including STANDARD truncation, do not change that identity.
- Reconstruction follows Event identity and source relationships. Missing or conflicting relationships are reported; the reader does not invent missing execution IDs or relationships. Record selection may make the reconstructed flow incomplete.

## 4. Compatibility and Migration Boundaries

The migration affects log producers, readers, configuration, and some runtime structures. The following boundaries distinguish changes to observability from the contracts retained by the programming model.

| Interface or stored data | Compatibility boundary |
|---|---|
| EventListener | Callback signatures, timing, and EventContext behavior are unchanged. Trace settings do not affect callback delivery or the Event payload received by listeners. |
| Custom Events | Construction and reconstruction retain the Event's ID, type, and attributes. Code that directly accesses the removed upstream fields must be updated, including EventListener implementations that read those fields. |
| Event JSON | The Event model no longer includes `upstreamEventId` or `upstreamActionName`; source relationships belong to Trace metadata. Compatibility with Event JSON from previous versions is not guaranteed. Producers, consumers, and external data must adopt the current Event schema. |
| ExecutionReporter | Java and Python component reporting APIs require an explicit execution ID. Callers can obtain it from a creation or start helper and must pass the same ID throughout one invocation or retry attempt, including duplicate reports, using a new ID for each subsequent attempt. Custom implementations must accept this explicit ID. Action lifecycle behavior and the output JSON fields are unchanged by this requirement. |
| Internal reporting helpers | ExecutionTraceContext, lifecycle Event factories, and reporting internals can be refactored without a separate deprecation period for each internal helper. |
| Custom log output | Custom output integrations must adapt to TraceRecord and the new recording-level semantics. Compatibility with the previous logging extension interfaces is outside scope. |
| Log format | Producers and the built-in reader use TraceRecord. Historical Event Log formats are unsupported. External queries and parsers must migrate field mappings and output metadata. |
| Operational naming | Loggers, log files, and related metrics adopt Trace naming. Existing log collection and monitoring configurations must be updated accordingly. |
| Configuration | Legacy logging keys are rejected at startup with migration guidance, as described in Section 3.3. |
| Persisted runtime state | Checkpoints and savepoints may contain the previous ActionTask and Event structures; ActionStateStore also persists triggering and output Events for result reuse. This proposal does not introduce cross-version migration for those structures. Upgrade guidance must identify affected restore and result-reuse paths; recovery and result reuse within the same version retain their existing behavior. |

Trace remains a best-effort account of runtime activity. A missing record does not establish that an Event or execution never occurred, and repeated records with the same Event ID may describe repeated observations of that Event. These limits apply to the logs; business execution and recovery retain their existing guarantees.
