---
title: Long-Term Memory
weight: 4
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

Long-Term Memory is a persistent storage mechanism in Flink Agents designed for storing large amounts of data across multiple agent runs with semantic search capabilities. It provides efficient storage, retrieval, and automatic compaction to manage memory capacity.

{{< hint info >}}
Long-Term Memory is built on vector stores, enabling semantic search to find relevant information based on meaning rather than exact matches.
{{< /hint >}}

## When to Use Long-Term Memory

Long-Term Memory is ideal for:

- **Large Document Collections**: Storing and searching through large amounts of text.
- **Conversation History**: Maintaining long conversation histories with semantic search.
- **Context Retrieval**: Finding relevant context from past interactions.

{{< hint warning >}}
Long-Term Memory is designed for retrieve concise and highly related context. For complete original data retrieval, consider using [Short-Term Memory]({{< ref "docs/development/memory/sensory_and_short_term_memory" >}}) instead.
{{< /hint >}}

## Data Structure

### Memory Item

`MemorySetItem` is the abstraction for representing an item stored in long-term memory. The item can be a piece of text, a chat message, a java or python object, semi-structured document, image, audio and video.

{{< hint info >}}
Currently, item can only be string and `ChatMessage`.
{{< /hint >}}

`MemorySetItem` has the following properties:
* **memory_set_name**: The name of the memory set this item belong to.
* **id**: The unique identifier of the memory item.
* **value**: The value of the memory item.
* **compacted**: Whether this item has been compacted.
* **created_time**: Timestamp or timestamp range for when this memory item was created.
* **last_accessed_time**: Timestamp for the last time this memory item was accessed.

### Memory Set

`MemorySet` is a set of memory items, which can be maintained and searched separately.

`MemorySet` has the following properties:
- **Name**: Unique identifier for the memory set
- **Item Type**: Type of items stored
- **Capacity**: Maximum number of items before compaction is triggered
- **Compaction Config**: Configuration for compaction

## Operations

### Creating and Getting Memory Set

{{< tabs "Memory Set Management" >}}

{{< tab "Python" >}}

```python
ltm = ctx.long_term_memory
# Get or create a memory set
memory_set: MemorySet = ltm.get_or_create_memory_set(
    name="my_memory_set",
    item_type=str,  # or ChatMessage
    capacity=50,
    compaction_config=CompactionConfig(
        model="my_chat_model",
        limit=1  # Number of summaries to generate
    )
)

# Get an existing memory set
memory_set: MemorySet = ltm.get_memory_set(name="my_memory_set")

# Delete a memory set
deleted: bool = ltm.delete_memory_set(name="my_memory_set")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
BaseLongTermMemory ltm = ctx.getLongTermMemory();
// Get or create a memory set
MemorySet memorySet =
        ltm.getOrCreateMemorySet(
                "my_memory_set", 
                String.class, 
                50, 
                new CompactionConfig("my_chat_model", 1));

// Get an existing memory set
memorySet = ltm.getMemorySet("my_memory_set");

// Delete a memory set
boolean deleted = ltm.deleteMemorySet("my_memory_set");
```
{{< /tab >}}

{{< /tabs >}}

### Adding Items

Add items to a memory set. When capacity is reached, compaction is automatically triggered:

{{< tabs "Adding Items" >}}

{{< tab "Python" >}}
```python
# Add a single item
item_id: List[str] = memory_set.add("This is a conversation message")

# Add multiple items
item_ids: List[str] = memory_set.add([
    "First message",
    "Second message",
    "Third message"
])

# Add with custom IDs
item_ids = memory_set.add(
    items=["Message 1", "Message 2"],
    ids=["msg_1", "msg_2"]
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Add a single item
String itemId = memorySet.add(List.of("This is a conversation message"), null, null).get(0);

// Add multiple items
List<String> itemIds = memorySet.add(List.of(
        "First message",
        "Second message",
        "Third message"
), null, null);

// Add with custom IDs
itemIds = memorySet.add(
    List.of("Message 1", "Message 2"),
    List.of("msg_1", "msg_2"),
    null
);
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
If no custom ids are provided, random id will be generated for each item.
{{< /hint >}}
### Retrieving Items

Retrieve items by ID or get all items:

{{< tabs "Retrieving Items" >}}

{{< tab "Python" >}}
```python
# Get a single item by ID
item: MemorySetItem = memory_set.get(ids="item_id_1")

# Get multiple items by IDs
items: List[MemorySetItem] = memory_set.get(ids=["item_id_1", "item_id_2"])

# Get all items if no IDs provided
all_items: List[MemorySetItem] = memory_set.get()

# Access item properties
for item in items:
    print(f"ID: {item.id}")
    print(f"Value: {item.value}")
    print(f"Compacted: {item.compacted}")
    print(f"Created: {item.created_time}")
    print(f"Last Accessed: {item.last_accessed_time}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Get a single item by ID
MemorySetItem item = memorySet.get(List.of("item_id_1")).get(0);

// Get multiple items by IDs
List<MemorySetItem> items = memorySet.get(List.of("item_id_1", "item_id_2"));

// Get all items if no IDs provided
List<MemorySetItem> allItems = memorySet.get(null);

// Access item properties
for (MemorySetItem myItem : items) {
    System.out.println("ID: " + item.getId());
    System.out.println("Value: " + item.getValue());
    System.out.println("Compacted: " + item.isCompacted());
    System.out.println("Created: " + item.getCreatedTime());
    System.out.println("Last Accessed: " + item.getLastAccessedTime());
}
```
{{< /tab >}}

{{< /tabs >}}

### Semantic Search

Search for relevant items using natural language queries:

{{< tabs "Semantic Search" >}}

{{< tab "Python" >}}
```python
# Search for relevant items
results: List[MemorySetItem] = memory_set.search(
    query="What did the user ask about?",
    limit=5
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Search for relevant items
List<MemorySetItem> results = memorySet.search(
    "What did the user ask about?",
    5,   // limit
    null // additional kwargs passed to vector store query            
);
```
{{< /tab >}}

{{< /tabs >}}

### Count Size

Check the current size of a memory set:

{{< tabs "Checking Size" >}}

{{< tab "Python" >}}
```python
# Get the current size
current_size = memory_set.size

# Check if capacity is reached
if memory_set.size >= memory_set.capacity:
    print("Capacity reached, compaction will be triggered on next add")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Get the current size
int currentSize = memorySet.size();

// Check if capacity is reached
if (currentSize >= memorySet.getCapacity()) {
    System.out.println("Capacity reached, compaction will be triggered on next add");
}
```
{{< /tab >}}

{{< /tabs >}}

## Usage in Agent

### Prerequisites

To use Long-Term Memory, you need:

1. **Vector Store**: A configured vector store (e.g., ChromaDB) - see [Vector Stores]({{< ref "docs/development/vector_stores" >}})
2. **Embedding Model**: An embedding model for converting text to vectors - see [Embedding Models]({{< ref "docs/development/embedding_models" >}})
3. **Chat Model** : Used for summarizing and combining related items.

### Configuration

Before using Long-Term Memory, you need to configure it in your agent execution environment.


| Key                                              | Default | Type                  | Description                                                                                    |
|--------------------------------------------------|---------|-----------------------|------------------------------------------------------------------------------------------------|
| AgentConfigOptions.JOB_IDENTIFIER                | job id  | String                | The unique identifier of the agent job, remaining consistent after restoring from a savepoint. |
| LongTermMemoryOptions.BACKEND                    | none    | LongTermMemoryBackend | The backend of the long-term memory.                                                           |
| LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME | none    | String                | The name of the vector store used as backend.                                                  |
| LongTermMemoryOptions.ASYNC_COMPACTION           | true    | boolean               | Execute compaction asynchronously.                                                             |

{{< tabs "Long-Term Memory Configuration" >}}

{{< tab "Python" >}}
```python
agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
agents_config = agents_env.get_config()

# Set job identifier
agents_config.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job")

# Configure long-term memory backend
agents_config.set(
    LongTermMemoryOptions.BACKEND,
    LongTermMemoryBackend.EXTERNAL_VECTOR_STORE
)

# Specify the vector store to use
agents_config.set(
    LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME,
    "my_vector_store"
)

# Enable async compaction
agents_config.set(LongTermMemoryOptions.ASYNC_COMPACTION, True)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
AgentsExecutionEnvironment agentsEnv = 
    AgentsExecutionEnvironment.getExecutionEnvironment(env);
Configuration agentsConfig = agentsEnv.getConfig();

// Set job identifier
agentsConfig.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job");

// Configure long-term memory backend
agentsConfig.set(
    LongTermMemoryOptions.BACKEND,
    LongTermMemoryBackend.EXTERNAL_VECTOR_STORE
);

// Specify the vector store to use
agentsConfig.set(
    LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME,
    "my_vector_store"
);

// Enable async compaction 
agentsConfig.set(LongTermMemoryOptions.ASYNC_COMPACTION, true);
```
{{< /tab >}}

{{< /tabs >}}

### Accessing Long-Term Memory

Long-Term Memory is accessed through the `RunnerContext` object:

{{< tabs "Accessing Long-Term Memory" >}}

{{< tab "Python" >}}

```python
@action(InputEvent)
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    # Access long-term memory
    ltm = ctx.long_term_memory
    
    # Get or create a memory set
    memory_set = ltm.get_or_create_memory_set(
        name="conversations",
        item_type=str,
        capacity=100,
        compaction_config=CompactionConfig(model="my_chat_model")
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void processEvent(InputEvent event, RunnerContext ctx) throws Exception {
    // Access long-term memory
    BaseLongTermMemory ltm = ctx.getLongTermMemory();

    // Get or create a memory set
    MemorySet memorySet = ltm.getOrCreateMemorySet(
        "conversations",
        String.class,
        100,
        new CompactionConfig("my_chat_model")
    );
}
```
{{< /tab >}}

{{< /tabs >}}

## Compaction

When capacity is reached, long-term memory will use LLM to summarize and combine related items.

User can configure the compaction config when create the `MemorySet`.
{{< tabs "Compaction Config" >}}

{{< tab "Python" >}}

```python
# Create memory set with compaction configuration.
memory_set = ltm.get_or_create_memory_set(
    name="conversations",
    item_type=str,
    capacity=10,  # The framework will automatically trigger compactions and try to maintain the 
                  # size of the memory set not exceeding the given capacity with best efforts
    compaction_config=CompactionConfig(model="my_chat_model", limit=1)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Create memory set with compaction configuration. 
MemorySet memorySet = ltm.getOrCreateMemorySet(
    "conversations",
    String.class,
    10, // The framework will automatically trigger compactions and try to maintain the 
        // size of the memory set not exceeding the given capacity with best efforts
    new CompactionConfig("my_chat_model", 1)
);
```
{{< /tab >}}

{{< /tabs >}}

### Async Compaction

Compactions are by default asynchronously performed, to avoid blocking the agent execution. You can also explicitly disable this, so that the agent execution will be paused during the compaction.
{{< tabs "Async Compaction" >}}

{{< tab "Python" >}}
```python
# Explicitly disable async compaction in configuration
agents_config.set(LongTermMemoryOptions.ASYNC_COMPACTION, False)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Explicitly disable async compaction in configuration
agentsConfig.set(LongTermMemoryOptions.ASYNC_COMPACTION, false);
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
When async compaction is enabled, compaction runs in a background thread. If compaction fails, errors are logged but don't cause the Flink job to fail.
{{< /hint >}}

{{< hint info >}}
When async compaction is enabled, compaction won't block user adding items to the memory set. The size of the memory set may exceed capacity temporarily.
{{< /hint >}}