---
title: Memory Events
weight: 5
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

Flink Agents can publish memory operations as framework-generated events. These events make it possible to audit what an action read or wrote, subscribe another action to memory changes, and inspect short-lived memory values from the Event Log for debugging.

When an action finishes, the framework folds the memory operations performed by that action into **memory events**. It emits at most one event for each memory scope and operation kind, for example one short-term write event and one sensory write event. Failed or unfinished actions do not emit memory events. An action failure fails the operator task, and recovery rebuilds the interpreter instead of continuing with later actions in the same interpreter instance.

Memory events use the same event infrastructure as other Flink Agents events. They can be written to the [Event Log]({{< ref "docs/operations/monitoring#event-log" >}}), observed by registered `EventListener`s, and used to trigger actions through normal event routing.

The framework can also emit an **agent-run begin event** (`_agent_run_begin_event`) when an `InputEvent` starts a run for a key. This opt-in event is disabled by default. When enabled, it is emitted before any action in that run executes and carries the key's short-term memory **values** as a flat map. Object nodes and empty-object structure are outside the event contract.

## When to Use

Memory events are useful when you need to:

- audit which memory entries an action changed;
- trigger follow-up actions from memory changes;
- inspect memory reads during debugging by enabling read events;
- inspect short-term or sensory values from an Event Log captured at `VERBOSE` level.

Memory events are not a replacement for the memory backends themselves. In particular, long-term memory events describe requested operations and observed read/search results; they do not provide a complete copy of the long-term memory store.

## Emission Model

Memory events are emitted at the action finish boundary, together with the user events produced by that action. The framework records memory activity while the action runs, folds the records by event type, and then appends the generated events to the action's output events.

Framework observation is best-effort. Values are normalized to the shared JSON wire contract at the event boundary. Unsupported short-term or sensory values are omitted from the generated event. Long-term observations are drained for the current partition key as one JSON batch; an invalid record is skipped after a valid batch is parsed, while a drain, serialization, or malformed-payload failure drops the batch. These observation failures do not change action success, and a long-term memory backend operation that already happened is not rolled back.

The folding rules are:

- one event per memory scope and operation kind;
- dot-separated memory paths for short-term and sensory `value` maps;
- last write or read wins when the same path is touched more than once by one action;
- operation-specific structured maps for long-term updates, gets, and searches;
- long-term search events keep the last result list for each memory-set/query pair.

Actions triggered by memory events can use memory, but those memory operations do not generate another layer of memory events. This prevents memory-event subscriptions from recursively producing more memory events.

## Configuration

Memory event generation is controlled per operation kind. Each operation resolves its switch in this order:

1. the operation-specific sub-key, if explicitly configured;
2. the `memory.generate-event` master switch, if explicitly configured;
3. the operation's built-in default.

| Key | Effective default | Description |
|-----|-------------------|-------------|
| `memory.generate-event` | — | Master switch. Fallback for unset sub-keys; when unset itself, per-operation defaults apply. |
| `memory.generate-event.short-term-write` | on | Short-term memory writes. |
| `memory.generate-event.short-term-read` | off | Short-term memory reads. |
| `memory.generate-event.sensory-write` | on | Sensory memory writes. |
| `memory.generate-event.sensory-read` | off | Sensory memory reads. |
| `memory.generate-event.long-term-update` | on | Long-term memory adds, updates, and deletes. |
| `memory.generate-event.long-term-get` | on | Long-term memory gets. |
| `memory.generate-event.long-term-search` | on | Long-term memory searches. |
| `agent-run.begin-event` | false | Opt-in agent-run begin event. Independent of the `memory.generate-event` master switch. |

The raw defaults of the eight `memory.generate-event*` options are unset; the on/off values above are the effective defaults after resolution. Registering an `EventListener` or subscribing an action to `_agent_run_begin_event` does not automatically enable the run-begin event. Set `agent-run.begin-event` to `true` when you need it.

{{< tabs "Memory Event Configuration" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.core_options import AgentExecutionOptions, MemoryEventOptions

config = agents_env.get_config()
# Turn everything off with the master switch...
config.set(MemoryEventOptions.MEMORY_GENERATE_EVENT, False)
# ...but keep short-term writes observable via the sub-key.
config.set(MemoryEventOptions.SHORT_TERM_WRITE, True)
# Opt in to the run-begin event through its independent switch.
config.set(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT, True)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.MemoryEventOptions;

Configuration config = agentsEnv.getConfig();
// Turn everything off with the master switch...
config.set(MemoryEventOptions.MEMORY_GENERATE_EVENT, false);
// ...but keep short-term writes observable via the sub-key.
config.set(MemoryEventOptions.SHORT_TERM_WRITE, true);
// Opt in to the run-begin event through its independent switch.
config.set(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT, true);
```
{{< /tab >}}

{{< tab "YAML" >}}
```yaml
agent:
  memory:
    generate-event: false
    generate-event.short-term-write: true
  agent-run:
    begin-event: true
```
{{< /tab >}}

{{< /tabs >}}

## Event Types

Flink Agents defines seven memory event types and one run-begin event:

| Event type | Typed class | Emitted for |
|------------|-------------|-------------|
| `_short_term_write_event` | `ShortTermWriteEvent` | Short-term memory writes of one action |
| `_short_term_read_event` | `ShortTermReadEvent` | Short-term memory reads of one action |
| `_sensory_write_event` | `SensoryWriteEvent` | Sensory memory writes of one action |
| `_sensory_read_event` | `SensoryReadEvent` | Sensory memory reads of one action |
| `_long_term_update_event` | `LongTermUpdateEvent` | Long-term memory adds, updates, and deletes of one action |
| `_long_term_get_event` | `LongTermGetEvent` | Long-term memory gets of one action |
| `_long_term_search_event` | `LongTermSearchEvent` | Long-term memory searches of one action |
| `_agent_run_begin_event` | `AgentRunBeginEvent` | Begin of one agent run (STM value snapshot) |

The seven memory event classes (Java: `org.apache.flink.agents.api.event`, Python: `flink_agents.api.events.memory_event`) share the abstract `MemoryEvent` base, and each class pins its own event type constant. `MemoryEvent.fromEvent(event)` (Python: `MemoryEvent.from_event(event)`) reconstructs the matching subclass from a generic event.

`AgentRunBeginEvent` is a lifecycle event rather than a `MemoryEvent` subclass. It has the same `key` and `value` attribute shape, but it is controlled by the opt-in `agent-run.begin-event` switch instead of the memory-event switches.

`LongTermSearchEvent` exposes the search shape as `getResults()` in Java and `.results` in Python: memory set to query string to ordered hit list.

### Wire Format

All memory events and run-begin events are emitted only for String-keyed streams and carry two common attributes:

| Attribute | Description |
|-----------|-------------|
| `key` | The String Flink key whose memory was observed. Non-String keys are skipped safely. |
| `value` | An operation-specific folded JSON map. Short-term, sensory, and run-begin snapshots use dot-key flat paths; long-term events use structured memory-set/item/query maps described below. |

The `key` and `value` fields are nested under `attributes`; they are not top-level event fields:

```json
{"timestamp": "2026-07-03T04:22:36.087914Z", "logLevel": "VERBOSE", "eventType": "_short_term_write_event", "event": {"eventType": "_short_term_write_event", "id": "da8ca017-25cc-4942-9085-ae38490e303c", "attributes": {"key": "user-42", "value": {"user.tier": "gold"}}, "type": "_short_term_write_event"}}
```

### Value Semantics

- **Dot-key flat maps.** Nested short-term and sensory memory paths are flattened with dot-separated keys. Writing `user.tier` produces `{"user.tier": "gold"}`, not a nested object.
- **Net effect.** Multiple operations on the same field within one action fold to the last value. The event describes the action's net effect, not every intermediate step.
- **Short-term and sensory update records.** Write events reuse the updates recorded for memory persistence. `newObject(path)` and `set(path, null)` can therefore both appear as `path: null`; consumers cannot distinguish an object marker from a real null value from the event alone.
- **Long-term updates.** `_long_term_update_event` values use `{"<set>": {"<item>": value}}`. A `null` item value means the item was deleted. The separate `cleared_sets` attribute lists whole sets deleted during the action. A set clear removes earlier folded writes for that set; writes after the clear remain in `value`.
- **Long-term gets.** `_long_term_get_event` values use `{"<set>": {"<item>": value}}` for returned items.
- **Long-term searches.** `_long_term_search_event` uses `{"<set>": {"<query>": [{"id": ..., "value": ..., "score": ...}]}}`. If the same query is searched more than once in one set during one action, the last hit list is kept.
- **Read-only payloads.** Subscribers should treat `value` payloads as read-only.

## Subscribing to Memory Events

Memory events participate in normal event routing, so actions can subscribe to them by event type.

{{< tabs "Subscribing to Memory Events" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event
from flink_agents.api.events.event_type import EventType
from flink_agents.api.events.memory_event import MemoryEvent
from flink_agents.api.runner_context import RunnerContext


class AuditingAgent(Agent):
    @action(EventType.ShortTermWriteEvent)
    @staticmethod
    def on_stm_write(event: Event, ctx: RunnerContext) -> None:
        write = MemoryEvent.from_event(event)
        print(f"key={write.key} wrote {write.value}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.MemoryEvent;

@Action(EventType.ShortTermWriteEvent)
public static void onStmWrite(Event event, RunnerContext ctx) {
    MemoryEvent write = MemoryEvent.fromEvent(event);
    System.out.printf("key=%s wrote %s%n", write.getKey(), write.getValue());
}
```
{{< /tab >}}

{{< /tabs >}}

## Inspecting Memory Values from the Event Log

Short-term and sensory changes can be followed offline from the Event Log if the job logs events at `VERBOSE` level. Write events may contain ambiguous `null` object markers, while run-begin events contain only value nodes, so these events are not a complete reconstruction format.

**Short-term memory** for a key at time *t*:

1. Locate the latest `_agent_run_begin_event` for that key at or before *t*. Its `value` is the short-term value snapshot at run begin.
2. Apply subsequent `_short_term_write_event` values for the same key in log order until *t*.

Because `agent-run.begin-event` is disabled by default, the Event Log has no built-in short-term value snapshot unless you opt in.

**Sensory memory** starts empty at each run begin. Apply that run's `_sensory_write_event` values in log order until *t*.

**Long-term memory cannot be reconstructed** from memory events. Long-term memory writes are model-mediated, so the requested update is not always the same as the final backend contents. Use long-term memory events to observe operation requests, trigger actions, and debug behavior, not as a backup of the long-term memory store.

## Semantics & Caveats

- **Event Log level.** Use `event-log.level: VERBOSE` when the full memory-event payload is required.
- **Durable execution.** A long-term memory operation wrapped in `durable_execute` is not recorded again when the durable result is replayed from state. The generated events describe operations that actually executed in the current attempt.
- **Observation failures.** Unsupported short-term or sensory values are omitted. Invalid long-term records are skipped individually after a valid batch is parsed, but a bridge, batch-serialization, or malformed-payload failure drops the current partition key's LTM observation batch. Warnings do not include observed values. These failures do not fail the action or roll back a completed long-term backend operation.
- **Failed actions.** A failed action does not reach the memory-event emission boundary. The operator task fails and recovery rebuilds the interpreter, so queued actions do not continue in the same interpreter instance after the failure.
- **Performance.** When enabled, the run-begin event scans the key's short-term memory on every input. Keep it disabled when you do not need offline value inspection.

{{< hint warning >}}
**At-least-once on recovery.** After a failure, replayed completed actions can re-log their persisted output events, including generated memory events. A replayed input can also re-emit its run-begin event with restored short-term memory. Event Log consumers should tolerate duplicates.
{{< /hint >}}

{{< hint warning >}}
**Short-term memory TTL.** The default `ON_READ_AND_WRITE` policy refreshes TTL on reads. If you enable `agent-run.begin-event`, its snapshot scan refreshes TTL for visited entries. Use `ON_CREATE_AND_WRITE` when entries should expire based on writes only.
{{< /hint >}}
