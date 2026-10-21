---
title: Sensory & Short-Term Memory
weight: 2
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
### Sensory Memory

Sensory memory is a temporary storage mechanism in Flink Agents designed for data that only needs to persist during a single agent run.

Sensory memory is automatically cleared after an agent run finishes, which provides isolation between agent runs. It provides a convenient way to store intermediate results, tool call contexts, and other temporary data without the overhead of persistence across multiple runs.

### Short-Term Memory

Short-Term Memory is shared across all actions within an agent run, and multiple agent runs with the same input key. This corresponds to Flink’s Keyed State, which is visible to processing of multiple records within the same keyed partition, and is not visible to processing of data in other keyed partitions.

## When to Use

Access Sensory and Short-Term Memory in the Action body, before or after awaiting a call.
Do not access them from a function submitted for async or durable execution, including
through a previously obtained `MemoryObject`. See
[Using Resources and Memory]({{< ref "docs/development/create-agent/actions#using-resources-and-memory" >}}).

### Sensory Memory
Sensory Memory is ideal for:

- **Intermediate results and temporary information** which will be used later by other actions in the same agent run.
- **Passing data through multiple actions**, reducing unnecessary data copying and serialization.

{{< hint warning >}}
Do not use Sensory Memory for data that needs to persist across multiple agent runs. Use Short-Term Memory or [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) instead.
{{< /hint >}}

### Short-Term Memory
Short-Term Memory is ideal for:

- **Persistent Data**: Data needs to persist across multiple runs.
- **Complete original data retrieval**: Users want to retrieve the exact same data they have written to memory.

{{< hint warning >}}
Short-Term Memory is designed for complete original data retrieval. For use cases that need concise and highly related context, consider using [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) instead.
{{< /hint >}}

## Data Types & Operations

Sensory memory and short-term memory have the same data types and operations. They support a hierarchical key-value structure.

### MemoryObject

Sensory memory and short-term memory each have a root `MemoryObject`, which users can use to store a series of key-value pairs.

### Supported Value Types

Keys stored in a `MemoryObject` must be strings. The supported value types differ between Java and Python.

**Java** supports a broad set of types:

- **Primitive Types**: integer, float, boolean, string
- **Collections**: list, map
- **Java POJOs**: See [Flink POJOs](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/fault-tolerance/serialization/types_serialization/#pojos) for details.
- **General Class Types**: Any objects that can be serialized by Kryo. See [General Class Types](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/fault-tolerance/serialization/types_serialization/#general-class-types) for details.

**Python** is restricted to recursively *checkpoint-stable* values:

- **Primitive Types**: `None`, `bool`, `int`, `float`, `str`, `bytes`
- **Collections**: `list`, and `dict` with `str` keys (values are recursively validated)

**Nested objects:** A `MemoryObject` is not a value passed to `set()`. Create nested object nodes with `new_object()` in Python or `newObject()` in Java, and then write their fields.

Anything else — Pydantic models, `uuid.UUID`, `Enum`, custom classes, `tuple`, `set`, `bytearray`, or a `dict` with non-`str` keys — is **rejected by `set()` with a `TypeError`**. Exact `bytes` is supported (it converts to a native Java `byte[]`), but `bytearray` and `bytes` subclasses are not — Pemja wraps them as non-checkpoint-stable objects rather than materializing a `byte[]`.

This is because Python values are converted across the Pemja boundary into Flink state, and only the types above materialize into native, checkpoint-stable JVM values; other objects would be stored as wrappers that fail on state restore. To store a richer object, materialize it to a primitive form first (e.g. `model.model_dump(mode="json")` for a Pydantic model, or `str(value)` for a UUID) and reconstruct it on read.

{{< hint warning >}}
Python memory values must be checkpoint-stable primitives, unlike the Java contract which also supports POJOs and Kryo-serializable objects. Python values materialize across the Pemja boundary before reaching Flink state, so models and other objects must be materialized first with `model_dump(mode="json")` (or `str(...)`) and reconstructed on read. Use exact `bytes` rather than `bytearray` for binary values, since only `bytes` materializes into a native `byte[]`.
{{< /hint >}}

### Read & Write

{{< tabs "Read & Write" >}}

{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
def process_event(event: Event, ctx: RunnerContext) -> None:
    memory: MemoryObject = ctx.sensory_memory # or ctx.short_term_memory
    # store primitive
    memory.set("primitive",  123)
    # store collection
    memory.set("collection", [1, 2, 3])
    # store a Pydantic model by materializing it to a checkpoint-stable dict first
    memory.set("model", my_model.model_dump(mode="json"))
    # store memory object
    obj1: MemoryObject = memory.new_object("obj1")
    obj1.set("field1", "foo")
    
    # read values from memory
    value1: int = memory.get("primitive")
    value2: List[int] = memory.get("collection")
    # reconstruct the Pydantic model on read
    model: MyModel = MyModel.model_validate(memory.get("model"))
    value4: MemoryObject = memory.get("obj1")
    value5: str = value4.get("field1")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void processEvent(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    MemoryObject memory = ctx.getSensoryMemory(); // ctx.getShortTermMemory();
    // store primitive
    memory.set("primitive", 123);
    // store collection
    memory.set("collection", List.of(1, 2, 3));
    // store java pojo
    memory.set("pojo", new WordWithCount("hello", "1"));
    // store general class types 
    memory.set("object", Prompt.fromText("the test {content}"));
    // store memory object
    MemoryObject obj1 = memory.newObject("obj1");  
    obj1.set("field1", "foo");
    
    // read values from memory
    int value1 = (int) memory.get("primitive").getValue();
    List<Integer> value2 = (List<Integer>) memory.get("collection").getValue();
    WordWithCount value3 = (WordWithCount) memory.get("pojo").getValue();
    Prompt value4 = (Prompt) memory.get("object").getValue();
    MemoryObject value5 = memory.get("obj1");
    String value6 = (String) value5.get("field1").getValue();
}
```
{{< /tab >}}

{{< /tabs >}}

#### Nested Object

There are two ways to access fields of a nested object. Users can choose whichever they like.
* Access fields from the innermost memory object with field names
* Access fields from an outer memory object with paths to the fields.

{{< tabs "Nested Object Access" >}}

{{< tab "Python" >}}
```python
# access fields from the innermost memory object with field names
user: MemoryObject = memory.new_object("user")
user.set("name", "john")
user.set("age", 13)

user: MemoryObject = memory.get("user")
name: str = user.get("name")
age: int = user.get("age")

# access fields from an outer memory object with paths to the fields
# any missing intermediate objects (here is user) will be created automatically.
memory.set("user.name", "jhon")
memory.set("user.age", 13)

name: str = memory.get("user.name")
age: int = memory.get("user.age")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// access fields from the innermost memory object with field names
MemoryObject user = memory.newObject("user", true);
user.set("name", "john");
user.set("age", 13);

user = memory.get("user");
String name = (String) user.get("name").getValue();
int age = (int) user.get("age").getValue();

// access fields from an outer memory object with paths to the fields
// any missing intermediate objects (here is user) will be created automatically.
memory.set("user.name", "john");
memory.set("user.age", 13);

name = (String) memory.get("user.name").getValue();
age = (int) memory.get("user.age").getValue();
```
{{< /tab >}}

{{< /tabs >}}

### Memory Reference

`MemoryRef` is a reference of the objects stored in memory. The `set` method of `MemoryObject` will return a `MemoryRef`.

#### When to use

`MemoryRef` is useful for passing data across multiple actions via memory. For large data, use a `MemoryRef` in events rather than the original data. It provides the following benefits:

* **Reduce the event payload size**: The size of a `MemoryRef` is typically smaller than that of the original data. Events are widely used in Flink Agents for action orchestration, observability, and fault tolerance. Therefore, reducing event size can help avoid unnecessary overhead.
* **Avoid unnecessary data copy & SerDe**: Only a reference to the data needs to be copied and serialized between actions.

{{< tabs "Memory Reference" >}}

{{< tab "Python" >}}
```python
@staticmethod
def first_action(event: Event, ctx: RunnerContext):
    ...
    sensory_memory = ctx.sensory_memory
    
    data_ref = sensory_memory.set(data_path, data_to_store)
    ctx.send_event(MyEvent(value=data_ref))
    ...

@action("MyEvent")
@staticmethod
def second_action(event: Event, ctx: RunnerContext):
    my_event = MyEvent.from_event(event)
    ...
    processed_data: ProcessedData = ctx.sensory_memory.get(my_event.value)
    # or
    processed_data: ProcessedData = my_event.value.resolve(ctx)
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject sensoryMemory = ctx.getSensoryMemory();

    MemoryRef dataRef = sensoryMemory.set(dataPath, dataToStore);
    ctx.sendEvent(new MyEvent(dataRef));
    ...
}

@Action("MyEvent")
public static void secondAction(Event event, RunnerContext ctx) throws Exception {
    MyEvent myEvent = MyEvent.fromEvent(event);
    ...
    MemoryObject sensoryMemory = ctx.getSensoryMemory();

    ProcessedData processedData = (ProcessedData) ctx.getSensoryMemory()
                                                     .get(myEvent.getValue())
                                                     .getValue();
    // or
    processedData = (ProcessedData) myEvent.getValue().resolve(ctx).getValue();
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

## Auto-Cleanup Behavior

### Sensory Memory

Sensory Memory is automatically cleared by the framework after each agent run completes. This cleanup happens:

- **When**: After the agent run finishes processing all events triggered by one input event.
- **What**: All data stored in sensory memory is cleared.
- **Why**: Isolation across agent runs.
- **Framework Responsibility**: The framework handles cleanup automatically; no user action required.

{{< hint info >}}
During execution, sensory memory data is checkpointed by Flink for fault tolerance. However, once the run completes, all sensory memory is cleared and will not be available in subsequent runs.
{{< /hint >}}

### Short-Term Memory

Short-term memory can be configured with a time-to-live (TTL) so that older state expires automatically. This is useful for agents that may run for a long time: if the agent only needs recent memories, expiring historical data directly keeps the stored state focused on the latest context and avoids retaining stale information.

Set `short-term-memory.state-ttl.ms` to a value greater than 0 in milliseconds to enable TTL. You can also configure how the TTL is refreshed and whether expired state can be returned before Flink cleans it up:

- `short-term-memory.state-ttl.update-type`: controls whether TTL is refreshed on create/write (`ON_CREATE_AND_WRITE`) or on read/write (`ON_READ_AND_WRITE`, the default).
- `short-term-memory.state-ttl.visibility`: controls whether expired memory is never returned or may be returned if it has not been cleaned up yet.

{{< hint warning >}}
With the default update type `ON_READ_AND_WRITE`, every read refreshes an entry's TTL. Enabling `agent-run.begin-event` introduces an additional source of reads: each input scans the key's short-term memory to produce the run-begin snapshot used by [Memory Observability]({{< ref "docs/development/memory/memory_events" >}}), which may extend the lifetime of the scanned entries even though only value nodes are included in the event. Leave `agent-run.begin-event` disabled if the snapshot is not needed. If the snapshot is needed but reads should not extend TTL, use `ON_CREATE_AND_WRITE`.
{{< /hint >}}

{{< tabs "Short-Term Memory TTL Configuration" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.core_options import (
    AgentExecutionOptions,
    ShortTermMemoryTtlUpdate,
    ShortTermMemoryTtlVisibility,
)
from flink_agents.api.execution_environment import AgentsExecutionEnvironment

agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
agents_config = agents_env.get_config()

agents_config.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, 60_000)
agents_config.set(
    AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
    ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE,
)
agents_config.set(
    AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
    ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
AgentsExecutionEnvironment agentsEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);
Configuration agentsConfig = agentsEnv.getConfig();

agentsConfig.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, 60_000L);
agentsConfig.set(
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
        ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE);
agentsConfig.set(
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
        ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED);
```
{{< /tab >}}

{{< /tabs >}}
