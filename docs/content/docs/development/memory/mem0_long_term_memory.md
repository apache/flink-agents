---
title: Mem0-based Long-Term Memory
weight: 6
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

Flink Agents provides built-in support for [Mem0](https://github.com/mem0ai/mem0) as a Long-Term Memory backend. Mem0 is an intelligent memory layer for AI agents that provides automatic memory extraction, consolidation, and semantic retrieval.

{{< hint info >}}
Mem0 replaces the previous VectorStore-based Long-Term Memory implementation since Flink Agents 0.3.0.
{{< /hint >}}

## Architecture

The integration uses a three-layer adapter pattern. `Mem0LongTermMemory` orchestrates a Mem0 `Memory` instance, and three adapters (`FlinkAgentsLLM`, `FlinkAgentsEmbedding`, `FlinkAgentsMem0VectorStore`) bridge Flink Agents resources (ChatModel, EmbeddingModel, VectorStore) to Mem0's factory system under the `flink_agents` provider. See [Adapter Mechanism](#adapter-mechanism-advanced) for details.

## Prerequisites

1. **Install Mem0 Python SDK**:
   ```bash
   pip install mem0ai
   ```

2. **Declare required resources** in your agent plan:
   - A [ChatModel]({{< ref "docs/development/chat_models" >}}) for Mem0's fact extraction and memory management
   - An [EmbeddingModel]({{< ref "docs/development/embedding_models" >}}) for vector generation
   - A [VectorStore]({{< ref "docs/development/vector_stores" >}}) for persistent storage (any `CollectionManageableVectorStore`)

## Configuration

Mem0 Long-Term Memory is enabled by setting three configuration options:

| Key                                                        | Type   | Description                     |
|------------------------------------------------------------|--------|---------------------------------|
| `long-term-memory.mem0.chat-model-setup`                   | String | Resource name of the chat model |
| `long-term-memory.mem0.embedding-model-setup`              | String | Resource name of the embedding model |
| `long-term-memory.mem0.vector-store`                       | String | Resource name of the vector store |

When all three options are configured, the framework automatically creates a `Mem0LongTermMemory` instance and attaches it to the `RunnerContext`.

### Configuration Example

{{< tabs "Mem0 Config" >}}

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

{{< /tabs >}}

## Data Model

### MemorySetItem

Represents a single memory item stored by Mem0:

| Field               | Type                  | Description                                |
|---------------------|-----------------------|--------------------------------------------|
| `memory_set_name`   | String                | Name of the memory set this item belongs to |
| `id`                | String                | Unique identifier of the item              |
| `value`             | String                | The memory content (extracted by Mem0)     |
| `created_at`        | Optional[datetime]    | When the item was created                  |
| `updated_at`        | Optional[datetime]    | When the item was last updated             |
| `additional_metadata` | Optional[Dict]      | Additional metadata associated with the item |

### MemorySet

A named collection of memory items. Memory sets are isolated through Mem0's hierarchical scoping (`user_id`, `agent_id`, `run_id`). In Flink Agents:
- `user_id` = job identifier
- `agent_id` = keyed partition key
- `run_id` = memory set name

## Operations

### Getting a Memory Set

Unlike the VectorStore-based LTM, Mem0 does not require explicit `get_or_create_memory_set` with capacity and compaction config — simply call `get_memory_set`:

{{< tabs "Get Memory Set" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.decorators import action
from flink_agents.api.events.event import InputEvent
from flink_agents.api.runner_context import RunnerContext

@action(InputEvent)
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    
    # Get (or create) a memory set
    memory_set = ltm.get_memory_set(name="conversations")
```

{{< /tab >}}

{{< /tabs >}}

### Adding Items

Add text items to a memory set. Mem0 automatically extracts and consolidates facts:

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

# Add multiple items with metadata
ids = memory_set.add(
    items=[
        "Python is great for data science.",
        "The weather in Paris is lovely in spring.",
    ],
    metadatas=[
        {"topic": "programming"},
        {"topic": "travel"},
    ]
)
```

{{< /tab >}}

{{< /tabs >}}

### Retrieving Items

Retrieve items by ID or list all items with optional filters:

{{< tabs "Retrieving Items" >}}

{{< tab "Python" >}}

```python
# Get a specific item by ID
items = memory_set.get(ids="mem_123abc")

# Get multiple items by IDs
items = memory_set.get(ids=["mem_123abc", "mem_456def"])

# Get all items (up to 100 by default)
all_items = memory_set.get()

# Get all items with metadata filter
work_items = memory_set.get(filters={"category": "work"})

# Get all items with custom limit
items = memory_set.get(limit=50)

# Access item properties
for item in items:
    print(f"ID: {item.id}")
    print(f"Value: {item.value}")
    print(f"Created: {item.created_at}")
    print(f"Updated: {item.updated_at}")
    print(f"Metadata: {item.additional_metadata}")
```

{{< /tab >}}

{{< /tabs >}}

### Semantic Search

Search for relevant memories using natural language:

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

{{< /tabs >}}

### Deleting a Memory Set

{{< tabs "Delete Memory Set" >}}

{{< tab "Python" >}}

```python
ltm = ctx.long_term_memory
deleted = ltm.delete_memory_set(name="conversations")
# Returns True
```

{{< /tab >}}

{{< /tabs >}}

## Usage in Agent

### Complete Example

Here's a complete example of a personalized assistant using Mem0 Long-Term Memory:

{{< tabs "Complete Example" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.decorators import action
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.memory.long_term_memory import LongTermMemoryOptions
from flink_agents.api.runner_context import RunnerContext

class PersonalizedAssistant:
    
    @action(InputEvent)
    @staticmethod
    def process_event(event: InputEvent, ctx: RunnerContext) -> None:
        """Respond to user using Mem0 long-term memory."""
        ltm = ctx.long_term_memory
        user_query = event.input
        
        # Get memory set for this session
        memory_set = ltm.get_memory_set(name="assistant_memories")
        
        # Search for relevant context from past interactions
        relevant = memory_set.search(query=user_query, limit=5)
        memory_context = "\n".join([f"- {m.value}" for m in relevant])
        
        # Generate response using your Agent logic
        # (e.g., with a chat model)
        prompt = f"Known context:\n{memory_context}\n\nUser: {user_query}"
        # ... call your LLM ...
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

{{< /tabs >}}

## Context Isolation

Mem0 Long-Term Memory supports Flink's keyed partition model through `agent_id` scoping:

{{< tabs "Context Isolation" >}}

{{< tab "Python" >}}

```python
from flink_agents.api.decorators import action
from flink_agents.api.events.event import InputEvent
from flink_agents.api.runner_context import RunnerContext

# Each keyed partition gets isolated memories
# user_id = job_id, agent_id = partition key, run_id = memory_set name

@action(InputEvent)
@staticmethod
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    memory_set = ltm.get_memory_set(name="user_data")
    
    # Add memory — automatically scoped to the current key
    memory_set.add(items=f"User said: {event.input}")
    
    # Search — only returns memories for the current key
    results = memory_set.search(query=event.input, limit=10)
```

{{< /tab >}}

{{< /tabs >}}

## Metadata Filtering

Add metadata when storing memories and use filters during retrieval/searches:

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

{{< /tabs >}}

## Adapter Mechanism (Advanced)

Mem0 integration relies on three custom adapters registered under the `flink_agents` provider:

- **FlinkAgentsLLM**: Wraps Flink Agents' `BaseChatModelSetup` — maps Mem0's LLM calls to the Flink Agents chat model API
- **FlinkAgentsEmbedding**: Wraps Flink Agents' `BaseEmbeddingModelSetup` — provides embeddings through Flink Agents' embedding model
- **FlinkAgentsMem0VectorStore**: Wraps any `CollectionManageableVectorStore` registered in Flink Agents — routes Mem0's vector operations to the configured store

These adapters are automatically registered with Mem0's factory system when the `Mem0LongTermMemory` instance is created.

## Best Practices

1. **Use metadata for organization**: Add relevant metadata when storing memories to enable precise filtering
2. **Be specific in queries**: More specific search queries yield better semantic results
3. **Mem0 handles extraction**: Unlike the old VectorStore LTM, Mem0 automatically extracts facts — no manual compaction needed
4. **Monitor token usage**: Mem0 makes two LLM calls per `add` operation (fact extraction + memory update), which impacts cost
5. **Choose appropriate vector store**: Use Chroma for development, Elasticsearch/OpenSearch for production

## Limitations

- **Python-only**: Mem0 integration is currently available only for Python agents
- **LLM dependency**: Every memory `add` requires LLM calls, adding latency and cost
- **No capacity-based compaction**: Mem0 manages memory internally; compaction is not configurable through Flink Agents
- **External dependency**: Requires the `mem0ai` Python package to be installed

For more details, refer to the [Mem0 Documentation](https://docs.mem0.ai/), including the [Python Quickstart](https://docs.mem0.ai/open-source/python-quickstart) and [Configuration Guide](https://docs.mem0.ai/open-source/configuration).
