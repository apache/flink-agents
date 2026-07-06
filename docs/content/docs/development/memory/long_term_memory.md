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

Long-Term Memory is a persistent storage mechanism in Flink Agents for storing information across multiple agent runs with semantic search capabilities. It provides automatic memory extraction, consolidation, and retrieval.

Long-Term Memory currently supports the [Mem0](https://github.com/mem0ai/mem0) backend. Mem0 is an intelligent memory layer that automatically extracts facts from conversations, consolidates related memories, and provides semantic retrieval — eliminating the need for manual memory management.

## Prerequisites

Declare the following resources in your agent plan:
- A [ChatModel]({{< ref "docs/development/chat_models" >}}) for memory extraction and management
- An [EmbeddingModel]({{< ref "docs/development/embedding_models" >}}) for vector generation
- A [VectorStore]({{< ref "docs/development/vector_stores" >}}) for persistent storage

> **Java prerequisite:** Mem0 invokes the chat and embedding models on its own thread executor, which relies on the async-friendly pemja fix. When Mem0 Long-Term Memory is configured together with Java actions, the runtime requires a Flink version of `1.20.5`, `2.0.2`, `2.1.3`, `2.2.1`, `2.3.0` or higher; otherwise it throws at startup. Either upgrade Flink or use the Python API.

## Configuration

Mem0 Long-Term Memory is enabled by setting three configuration options:

| Key                                        | Type   | Description                      |
|--------------------------------------------|--------|----------------------------------|
| `long-term-memory.mem0.chat-model-setup`   | String | Resource name of the chat model  |
| `long-term-memory.mem0.embedding-model-setup` | String | Resource name of the embedding model |
| `long-term-memory.mem0.vector-store`       | String | Resource name of the vector store |

When all three options are configured, the framework automatically creates a Mem0-based Long-Term Memory instance and attaches it to the `RunnerContext`.

### Configuration Example

{{< tabs "LTM Configuration" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.memory.long_term_memory import LongTermMemoryOptions

env = AgentsExecutionEnvironment.get_execution_environment()
agents_config = env.get_config()

# Set job identifier (maps to Mem0 user_id)
agents_config.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job")

# Configure Mem0 Long-Term Memory
agents_config.set(
    LongTermMemoryOptions.Mem0.CHAT_MODEL_SETUP,
    "my_chat_model"
)
agents_config.set(
    LongTermMemoryOptions.Mem0.EMBEDDING_MODEL_SETUP,
    "my_embedding_model"
)
agents_config.set(
    LongTermMemoryOptions.Mem0.VECTOR_STORE,
    "my_vector_store"
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
AgentsExecutionEnvironment agentsEnv = 
    AgentsExecutionEnvironment.getExecutionEnvironment(env);
Configuration agentsConfig = agentsEnv.getConfig();

// Set job identifier (maps to Mem0 user_id)
agentsConfig.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job");

// Configure Mem0 Long-Term Memory
agentsConfig.set(
    LongTermMemoryOptions.Mem0.CHAT_MODEL_SETUP,
    "my_chat_model"
);
agentsConfig.set(
    LongTermMemoryOptions.Mem0.EMBEDDING_MODEL_SETUP,
    "my_embedding_model"
);
agentsConfig.set(
    LongTermMemoryOptions.Mem0.VECTOR_STORE,
    "my_vector_store"
);
```

{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
If `JOB_IDENTIFIER` is not configured, the Flink job ID will be used by default.
{{< /hint >}}

## Data Model

### MemorySetItem

Represents a single memory item stored in Long-Term Memory:

| Field               | Type                  | Description                                |
|---------------------|-----------------------|--------------------------------------------|
| `memory_set_name`   | String                | Name of the memory set this item belongs to |
| `id`                | String                | Unique identifier of the item              |
| `value`             | String                | The memory content (extracted by Mem0)     |
| `created_at`        | Optional[DateTime]    | When the item was created                  |
| `updated_at`        | Optional[DateTime]    | When the item was last updated             |
| `additional_metadata` | Optional[Map]       | Additional metadata associated with the item |

### MemorySet

A named collection of memory items. Memory sets provide logical grouping and isolation of memories. See [Context Isolation](#context-isolation) for details on how memories are scoped and isolated.

## Operations

### Getting a Memory Set

{{< tabs "Get Memory Set" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.decorators import action
from flink_agents.api.events.event import InputEvent, Event
from flink_agents.api.runner_context import RunnerContext

@action(InputEvent.EVENT_TYPE)
@staticmethod
def process_event(event: Event, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    
    # Get (or create) a memory set
    memory_set = ltm.get_memory_set(name="conversations")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
@Action(listenEventTypes = {InputEvent.EVENT_TYPE})
public static void processEvent(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    BaseLongTermMemory ltm = ctx.getLongTermMemory();

    // Get (or create) a memory set
    MemorySet memorySet = ltm.getMemorySet("conversations");
}
```

{{< /tab >}}

{{< /tabs >}}

### Adding Items

{{< tabs "Adding Items" >}}

{{< tab "Python" >}}

```python
# Add a single item
ids = memory_set.add(items="The user prefers Python over Java.")

# Add multiple items
ids = memory_set.add(items=[
    "User likes coffee in the morning.",
    "User works from home on Fridays.",
])

# Add with metadata
ids = memory_set.add(
    items="Important meeting tomorrow.",
    metadatas={"category": "work"}
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Add a single item
List<String> ids = memorySet.add(
    List.of("The user prefers Python over Java."), null);

// Add multiple items
ids = memorySet.add(List.of(
    "User likes coffee in the morning.",
    "User works from home on Fridays."
), null);

// Add with metadata
ids = memorySet.add(
    List.of("Important meeting tomorrow."),
    List.of(Map.of("category", "work"))
);
```

{{< /tab >}}

{{< /tabs >}}

### Retrieving Items

{{< tabs "Retrieving Items" >}}

{{< tab "Python" >}}

```python
# Get a specific item by ID
items = memory_set.get(ids="mem_123abc")

# Get multiple items by IDs
items = memory_set.get(ids=["mem_123abc", "mem_456def"])

# Get all items
all_items = memory_set.get()

# Get with metadata filter
work_items = memory_set.get(filters={"category": "work"})

# Access item properties
for item in items:
    print(f"ID: {item.id}")
    print(f"Value: {item.value}")
    print(f"Created: {item.created_at}")
    print(f"Updated: {item.updated_at}")
    print(f"Metadata: {item.additional_metadata}")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Get a specific item by ID
List<MemorySetItem> items = memorySet.get(List.of("item_id_1"), null, null);

// Get multiple items by IDs
items = memorySet.get(List.of("item_id_1", "item_id_2"), null, null);

// Get all items
List<MemorySetItem> allItems = memorySet.get(null, null, null);

// Get with metadata filter
List<MemorySetItem> workItems = memorySet.get(null, Map.of("category", "work"), null);

// Access item properties
for (MemorySetItem item : items) {
    System.out.println("ID: " + item.getId());
    System.out.println("Value: " + item.getValue());
    System.out.println("Created: " + item.getCreatedAt());
    System.out.println("Updated: " + item.getUpdatedAt());
    System.out.println("Metadata: " + item.getAdditionalMetadata());
}
```

{{< /tab >}}

{{< /tabs >}}

### Semantic Search

{{< tabs "Semantic Search" >}}

{{< tab "Python" >}}

```python
# Basic search
results = memory_set.search(
    query="What does the user like?",
    limit=5,
)

# Search with metadata filter
results = memory_set.search(
    query="programming languages",
    limit=5,
    filters={"topic": "programming"},
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Basic search
List<MemorySetItem> results = memorySet.search(
    "What does the user like?",
    5,
    null,
    Map.of()
);

// Search with metadata filter
results = memorySet.search(
    "programming languages",
    5,
    Map.of("topic", "programming"),
    Map.of()
);
```

{{< /tab >}}

{{< /tabs >}}

### Deleting Items

{{< tabs "Deleting Items" >}}

{{< tab "Python" >}}

```python
# Delete specific items by ID
memory_set.delete(ids="mem_123abc")

# Delete multiple items
memory_set.delete(ids=["mem_123abc", "mem_456def"])

# Delete all items in the memory set
memory_set.delete()
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Delete specific items by ID
memorySet.delete(List.of("item_id_1"));

// Delete multiple items
memorySet.delete(List.of("item_id_1", "item_id_2"));

// Delete all items in the memory set
memorySet.delete(null);
```

{{< /tab >}}

{{< /tabs >}}

### Deleting a Memory Set

{{< tabs "Delete Memory Set" >}}

{{< tab "Python" >}}

```python
ltm = ctx.long_term_memory
deleted = ltm.delete_memory_set(name="conversations")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
BaseLongTermMemory ltm = ctx.getLongTermMemory();
boolean deleted = ltm.deleteMemorySet("conversations");
```

{{< /tab >}}

{{< /tabs >}}

### Metadata Filtering

Add metadata when storing memories and use filters during retrieval and search:

{{< tabs "Metadata Filtering" >}}

{{< tab "Python" >}}

```python
# Store with metadata
memory_set.add(
    items="User prefers functional programming.",
    metadatas={"topic": "programming", "confidence": "high"}
)

# Retrieve with filter
results = memory_set.get(filters={"topic": "programming"})

# Search with filter
results = memory_set.search(
    query="what programming language",
    limit=5,
    filters={"confidence": "high"}
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Store with metadata
memorySet.add(
    List.of("User prefers functional programming."),
    List.of(Map.of("topic", "programming", "confidence", "high"))
);

// Retrieve with filter
List<MemorySetItem> results = memorySet.get(null, Map.of("topic", "programming"), null);

// Search with filter
results = memorySet.search(
    "what programming language",
    5,
    Map.of("confidence", "high"),
    Map.of()
);
```

{{< /tab >}}

{{< /tabs >}}

## Usage in Agent

### Usage Snippet

The snippets below show how to read from and write to Long-Term Memory inside an action.

{{< tabs "Usage Snippet" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.decorators import action
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.events.event import InputEvent, OutputEvent, Event
from flink_agents.api.memory.long_term_memory import LongTermMemoryOptions
from flink_agents.api.runner_context import RunnerContext

class PersonalizedAssistant:
    
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_event(event: Event, ctx: RunnerContext) -> None:
        """Respond to user using long-term memory."""
        ltm = ctx.long_term_memory
        user_query = InputEvent.from_event(event).input
        
        # Get memory set
        memory_set = ltm.get_memory_set(name="assistant_memories")
        
        # Search for relevant context from past interactions
        relevant = memory_set.search(query=user_query, limit=5)
        memory_context = "\n".join([f"- {m.value}" for m in relevant])
        
        # Generate response using your Agent logic
        prompt = f"Known context:\n{memory_context}\n\nUser: {user_query}"
        response = f"Response to: {user_query}"
        
        # Store the interaction
        memory_set.add(items=f"User asked about: {user_query}")
        
        ctx.send_event(OutputEvent(output=response))

# Setup
env = AgentsExecutionEnvironment.get_execution_environment()
agents_config = env.get_config()
agents_config.set(AgentConfigOptions.JOB_IDENTIFIER, "personalized_assistant")
agents_config.set(LongTermMemoryOptions.Mem0.CHAT_MODEL_SETUP, "my_chat_model")
agents_config.set(LongTermMemoryOptions.Mem0.EMBEDDING_MODEL_SETUP, "my_embedding_model")
agents_config.set(LongTermMemoryOptions.Mem0.VECTOR_STORE, "my_vector_store")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
@Action(listenEventTypes = {InputEvent.EVENT_TYPE})
public static void processEvent(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    String userQuery = String.valueOf(inputEvent.getInput());

    // Get memory set
    MemorySet memorySet = ltm.getMemorySet("assistant_memories");

    // Search for relevant context from past interactions
    List<MemorySetItem> relevant = memorySet.search(userQuery, 5, null, Map.of());
    StringBuilder memoryContext = new StringBuilder();
    for (MemorySetItem item : relevant) {
        memoryContext.append("- ").append(item.getValue()).append("\n");
    }

    // Generate response using your Agent logic
    String response = "Response to: " + userQuery;

    // Store the interaction
    memorySet.add(List.of("User asked about: " + userQuery), null);

    ctx.sendEvent(new OutputEvent(response));
}
```

{{< /tab >}}

{{< /tabs >}}

## Context Isolation

Long-Term Memory automatically provides context isolation through Flink's keyed partition model. Each keyed partition maintains its own isolated set of memories, ensuring that memories from one user or session do not leak into another.

The isolation hierarchy works as follows:
- **Job-level** (`JOB_IDENTIFIER`): Separates memories between different Flink jobs
- **Partition-level** (keyed partition key): Separates memories between different keys within the same job
- **Set-level** (memory set name): Separates memories between different logical categories within the same partition

This means you can reuse the same memory set name across different partitions, and each partition will normally access only its own memories.

> **Note:** Partition-level isolation is currently derived from the hash of the partition key (`String.valueOf(key.hashCode())`) rather than the full original key. Distinct keys whose hashes collide may therefore share the same memory context. Avoid relying on isolation as a strict security boundary; if collision-free isolation is required, encode a unique identifier into the memory set name.
