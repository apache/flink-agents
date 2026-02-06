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

Sensory memory will be auto cleaned after an agent run finished, which means isolation between agent runs. It provides a convenient way to store intermediate results, tool call contexts, and other temporary data without the overhead of persistence across multiple runs.

### Short-Term Memory

Short-Term Memory is shared across all actions within an agent run, and multiple agent runs with the same input key. This corresponds to Flink’s Keyed State, which is visible to processing of multiple records within the same keyed partition, and is not visible to processing of data in other keyed partitions.

## When to Use

### Sensory Memory
Sensory Memory is ideal for:

- **Intermediate results and temporary information** which will be used later by other actions in the same agent run.
- **Passing data through multiple actions**, reduce unnecessary data copy and serialization.

{{< hint warning >}}
Do not use Sensory Memory for data that needs to persist across multiple agent runs. Use Short-Term Memory or [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) instead.
{{< /hint >}}

### Short-Term Memory
Short-Term Memory is ideal for:

- **Persistent Data**: Data needs to persist across multiple runs.
- **Complete original data retrieval**: User want to retrieve the exact same data they have written to memory.

{{< hint warning >}}
Short-Term Memory is designed for complete original data retrival. For use case that need get the concise and highly related context, consider using [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) instead.
{{< /hint >}}

## Data Types & Operations

Sensory memory and short-term memory have the same data types and operations. They support a hierarchical key-value structure.

### MemoryObject

The root of the sensory memory and short-term memory is `MemoryObject`. User can use it to store a series of key-value pairs.

### Supported Value Types

The key of the pairs store in `MemoryObject` must be string, and the value can be follow types

- **Primitive Types**: integer, float, boolean, string
- **Collections**: list, map
- **Java POJOs**: See [Flink POJOs](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/fault-tolerance/serialization/types_serialization/#pojos) for details.
- **General Class Types**: Any objects can be serialized by kryo. See [General Class Types](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/fault-tolerance/serialization/types_serialization/#general-class-types) for details.
- **Memory Object**: The value can also be a `MemoryObject`, which means user can store nested objects.

### Read & Write

{{< tabs "Read & Write" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    memory: MemoryObject = ctx.sensory_memory # or ctx.short_term_memory
    # store primitive
    memory.set("primitive",  123)
    # store collection
    memory.set("collection", [1, 2, 3])
    # store general class types
    memory.set("object", Prompt.from_text("the test {content}"))
    # store memory object
    obj1: MemoryObject = memory.new_object("obj1")
    obj1.set("field1", "foo")
    
    # read values from memory
    value1: int = memory.get("primitive")
    value2: List[int] = memory.get("collection")
    value3: Prompt = memory.get("object")
    value4: MemoryObject = memory.get("obj1")
    value5: str = value4.get("field1")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void processEvent(InputEvent event, RunnerContext ctx) throws Exception {
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

{{< hint info >}}
Unlike other types, user should use `new_object` to write a `MemoryObject`.
{{< /hint >}}

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

`MemoryRef` is useful for passing data across multiple actions via memory. We recommend user to use `MemoryRef` for large data in events rather than original data. It can bring follow benefit:
* **Reduce the event payload size**: The size of `MemoryRef` is usually typically smaller than that of the original data. Events are widely used in Flink Agents - action orchestration, observability and fault tolerance, etc. Therefore, reducing the event size can help avoid unnecessary overheads.
* **Avoid unnecessary data copy & SerDe**: When traveling through actions, only the reference of data needs to be copied and serialized.

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

@action(MyEvent)
@staticmethod
def second_action(event: Event, ctx: RunnerContext):
    ...
    processed_data: ProcessedData = ctx.sensory_memory.get(event.value)
    # or
    processed_data: ProcessedData = event.value.resolve(ctx)
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject sensoryMemory = ctx.getSensoryMemory();

    MemoryRef dataRef = sensoryMemory.set(dataPath, dataToStore);
    ctx.sendEvent(new MyEvent(dataRef));
    ...
}

@Action(listenEvents = {MyEvent.class})
public static void secondAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject sensoryMemory = ctx.getSensoryMemory();

    ProcessedData processedData = (ProcessedData) ctx.getSensoryMemory()
                                                     .get(event.getValue())
                                                     .getValue();
    // or
    processedData = (ProcessedData) event.getValue().resolve(ctx);
    ...
}
```
{{< /tab >}}

{{< /tabs >}}
## Auto-Cleanup Behavior

Sensory Memory is automatically cleared by the framework after each agent run completes. This cleanup happens:

- **When**: After the agent run finishes processing all events trigger by one input event.
- **What**: All data stored in sensory memory is cleared.
- **Why**: Isolation across agent runs.
- **Framework Responsibility**: The framework handles cleanup automatically; no user action required.

{{< hint info >}}
During execution, sensory memory data is checkpointed by Flink for fault tolerance. However, once the run completes, all sensory memory is cleared and will not be available in subsequent runs.
{{< /hint >}}