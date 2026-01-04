---
title: Long-Term Memory
weight: 9
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

### Key Characteristics

- **Persistent Storage**: Data persists across multiple agent runs
- **Semantic Search**: Find relevant information using natural language queries
- **Capacity Management**: Automatic compaction when capacity is reached
- **Vector Store Backend**: Built on vector stores for efficient similarity search
- **Multiple Item Types**: Supports string and ChatMessage types
- **Async Compaction**: Optional asynchronous compaction to avoid blocking operations

## When to Use Long-Term Memory

Long-Term Memory is ideal for:

- **Large Document Collections**: Storing and searching through large amounts of text
- **Conversation History**: Maintaining long conversation histories with semantic search
- **Knowledge Bases**: Building searchable knowledge bases for RAG (Retrieval-Augmented Generation)
- **Experience Storage**: Storing agent experiences and learnings that need to be retrieved semantically
- **Context Retrieval**: Finding relevant context from past interactions

{{< hint warning >}}
Long-Term Memory is designed for large-scale data. For small, precise data that needs fast access, consider using [Short-Term Memory]({{< ref "docs/development/workflow_agent" >}}#short-term-memory) instead.
{{< /hint >}}

## Key Concepts

### Memory Classification

Flink Agents classifies memory types based on three dimensions:

| Dimension | Description |
|-----------|-------------|
| **Visibility** | Scope of memory access (single-key vs cross-key) |
| **Retention** | Lifecycle duration (single run vs multiple runs) |
| **Accuracy** | Information completeness (precise vs vague/compacted) |

Long-Term Memory characteristics:

- **Visibility**: Single-key (scoped to the current key)
- **Retention**: Multiple Runs (persists across runs)
- **Accuracy**: Vague (supports compaction, information may be summarized)

### Memory Sets

Long-Term Memory organizes data into **Memory Sets** - named collections of memory items. Each memory set has:

- **Name**: Unique identifier for the memory set
- **Item Type**: Type of items stored (currently `str` or `ChatMessage`)
- **Capacity**: Maximum number of items before compaction is triggered
- **Compaction Strategy**: Strategy for managing capacity (currently supports summarization)

### Storage Backend

Long-Term Memory uses vector stores as the storage backend:

- **Vector Embeddings**: Items are converted to embeddings for semantic search
- **Collection Management**: Each memory set maps to a vector store collection
- **Metadata Storage**: Stores timestamps, compaction status, and custom metadata
- **Semantic Search**: Enables finding relevant items by meaning

## Configuration

Before using Long-Term Memory, you need to configure it in your agent execution environment:

{{< tabs "Long-Term Memory Configuration" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.memory.long_term_memory import (
    LongTermMemoryBackend,
    LongTermMemoryOptions,
)

agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
agents_config = agents_env.get_config()

# Set job identifier (required)
agents_config.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job_id")

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

# Enable async compaction (optional, default: False)
agents_config.set(LongTermMemoryOptions.ASYNC_COMPACTION, True)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.core_options.AgentConfigOptions;
import org.apache.flink.agents.api.memory.long_term_memory.LongTermMemoryBackend;
import org.apache.flink.agents.api.memory.long_term_memory.LongTermMemoryOptions;

AgentsExecutionEnvironment agentsEnv = 
    AgentsExecutionEnvironment.getExecutionEnvironment(env);
ReadableConfiguration agentsConfig = agentsEnv.getConfig();

// Set job identifier (required)
agentsConfig.set(AgentConfigOptions.JOB_IDENTIFIER, "my_job_id");

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

// Enable async compaction (optional, default: false)
agentsConfig.set(LongTermMemoryOptions.ASYNC_COMPACTION, true);
```
{{< /tab >}}

{{< /tabs >}}

### Prerequisites

To use Long-Term Memory, you need:

1. **Vector Store**: A configured vector store (e.g., ChromaDB) - see [Vector Stores]({{< ref "docs/development/vector_stores" >}})
2. **Embedding Model**: An embedding model for converting text to vectors
3. **Chat Model** (for summarization): If using summarization compaction strategy

## Accessing Long-Term Memory

Long-Term Memory is accessed through the `RunnerContext` object:

{{< tabs "Accessing Long-Term Memory" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.runner_context import RunnerContext

@action(InputEvent)
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    # Access long-term memory
    ltm = ctx.long_term_memory
    
    # Get or create a memory set
    memory_set = ltm.get_or_create_memory_set(
        name="conversations",
        item_type=str,
        capacity=100,
        compaction_strategy=SummarizationStrategy(model="my_chat_model")
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.context.RunnerContext;

@Action(listenEvents = {InputEvent.class})
public static void processEvent(InputEvent event, RunnerContext ctx) throws Exception {
    // Access long-term memory
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    
    // Get or create a memory set
    MemorySet memorySet = ltm.getOrCreateMemorySet(
        "conversations",
        String.class,
        100,
        new SummarizationStrategy("my_chat_model")
    );
}
```
{{< /tab >}}

{{< /tabs >}}

## Memory Set Operations

### Creating and Getting Memory Sets

{{< tabs "Memory Set Management" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.memory.long_term_memory import (
    MemorySet,
    SummarizationStrategy,
)

# Get or create a memory set
memory_set = ltm.get_or_create_memory_set(
    name="my_memory_set",
    item_type=str,  # or ChatMessage
    capacity=50,
    compaction_strategy=SummarizationStrategy(
        model="my_chat_model",
        limit=1  # Number of summaries to generate
    )
)

# Get an existing memory set
memory_set = ltm.get_memory_set(name="my_memory_set")

# Delete a memory set
deleted = ltm.delete_memory_set(name="my_memory_set")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.memory.long_term_memory.MemorySet;
import org.apache.flink.agents.api.memory.long_term_memory.SummarizationStrategy;

// Get or create a memory set
MemorySet memorySet = ltm.getOrCreateMemorySet(
    "my_memory_set",
    String.class,  // or ChatMessage.class
    50,
    new SummarizationStrategy("my_chat_model", 1)  // limit = 1
);

// Get an existing memory set
MemorySet memorySet = ltm.getMemorySet("my_memory_set");

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
item_id = memory_set.add("This is a conversation message")

# Add multiple items
item_ids = memory_set.add([
    "First message",
    "Second message",
    "Third message"
])

# Add with custom IDs
item_ids = memory_set.add(
    items=["Message 1", "Message 2"],
    ids=["msg_1", "msg_2"]
)

# Add ChatMessage items
from flink_agents.api.chat_message import ChatMessage, MessageRole

message = ChatMessage(
    role=MessageRole.USER,
    content="User question here"
)
item_id = memory_set.add(message)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Add a single item
String itemId = memorySet.add("This is a conversation message").get(0);

// Add multiple items
List<String> itemIds = memorySet.add(Arrays.asList(
    "First message",
    "Second message",
    "Third message"
));

// Add with custom IDs
List<String> itemIds = memorySet.add(
    Arrays.asList("Message 1", "Message 2"),
    Arrays.asList("msg_1", "msg_2")
);

// Add ChatMessage items
import org.apache.flink.agents.api.chat_message.ChatMessage;
import org.apache.flink.agents.api.chat_message.MessageRole;

ChatMessage message = new ChatMessage(
    MessageRole.USER,
    "User question here"
);
String itemId = memorySet.add(message).get(0);
```
{{< /tab >}}

{{< /tabs >}}

### Retrieving Items

Retrieve items by ID or get all items:

{{< tabs "Retrieving Items" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.memory.long_term_memory import MemorySetItem

# Get a single item by ID
item: MemorySetItem = memory_set.get(ids="item_id_1")

# Get multiple items by IDs
items: List[MemorySetItem] = memory_set.get(ids=["item_id_1", "item_id_2"])

# Get all items
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
import org.apache.flink.agents.api.memory.long_term_memory.MemorySetItem;

// Get a single item by ID
MemorySetItem item = memorySet.get("item_id_1");

// Get multiple items by IDs
List<MemorySetItem> items = memorySet.get(Arrays.asList("item_id_1", "item_id_2"));

// Get all items
List<MemorySetItem> allItems = memorySet.get();

// Access item properties
for (MemorySetItem item : items) {
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

# Search with additional parameters
results = memory_set.search(
    query="Find information about errors",
    limit=10,
    # Additional kwargs passed to vector store query
    score_threshold=0.7
)

# Process search results
for item in results:
    print(f"Found: {item.value}")
    print(f"Relevance score available in metadata: {item.additional_metadata}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Search for relevant items
List<MemorySetItem> results = memorySet.search(
    "What did the user ask about?",
    5  // limit
);

// Process search results
for (MemorySetItem item : results) {
    System.out.println("Found: " + item.getValue());
    // Additional metadata available in item.getAdditionalMetadata()
}
```
{{< /tab >}}

{{< /tabs >}}

### Deleting Items

Delete specific items or all items from a memory set:

{{< tabs "Deleting Items" >}}

{{< tab "Python" >}}
```python
# Delete a single item
memory_set.delete(ids="item_id_1")

# Delete multiple items
memory_set.delete(ids=["item_id_1", "item_id_2"])

# Delete all items
memory_set.delete()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Delete a single item
memorySet.delete("item_id_1");

// Delete multiple items
memorySet.delete(Arrays.asList("item_id_1", "item_id_2"));

// Delete all items
memorySet.delete();
```
{{< /tab >}}

{{< /tabs >}}

### Checking Memory Set Size

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
int currentSize = memorySet.getSize();

// Check if capacity is reached
if (currentSize >= memorySet.getCapacity()) {
    System.out.println("Capacity reached, compaction will be triggered on next add");
}
```
{{< /tab >}}

{{< /tabs >}}

## Compaction

Compaction is the process of reducing memory set size when capacity is reached. Currently, Flink Agents supports **Summarization Strategy**.

### Summarization Strategy

The summarization strategy uses an LLM to summarize multiple items into fewer items:

- **Trigger**: Automatically triggered when `memory_set.size >= memory_set.capacity`
- **Process**: Groups items into topics and generates summaries
- **Result**: Original items are deleted and replaced with summarized versions
- **Metadata**: Summarized items track which original items they represent

{{< tabs "Summarization Strategy" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.memory.long_term_memory import SummarizationStrategy

# Create memory set with summarization strategy
memory_set = ltm.get_or_create_memory_set(
    name="conversations",
    item_type=str,
    capacity=10,  # Compaction triggers when size >= 10
    compaction_strategy=SummarizationStrategy(
        model="my_chat_model",  # LLM to use for summarization
        limit=3,  # Number of summary topics to generate
        prompt=None  # Optional custom prompt, uses default if None
    )
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.memory.long_term_memory.SummarizationStrategy;

// Create memory set with summarization strategy
MemorySet memorySet = ltm.getOrCreateMemorySet(
    "conversations",
    String.class,
    10,  // Compaction triggers when size >= 10
    new SummarizationStrategy(
        "my_chat_model",  // LLM to use for summarization
        3,  // Number of summary topics to generate
        null  // Optional custom prompt, uses default if null
    )
);
```
{{< /tab >}}

{{< /tabs >}}

### Async Compaction

Compaction can be executed asynchronously to avoid blocking operations:

{{< tabs "Async Compaction" >}}

{{< tab "Python" >}}
```python
# Enable async compaction in configuration
agents_config.set(LongTermMemoryOptions.ASYNC_COMPACTION, True)

# When enabled, compaction runs in background
# Operations continue without waiting for compaction to complete
memory_set.add("New item")  # If capacity reached, compaction runs async
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Enable async compaction in configuration
agentsConfig.set(LongTermMemoryOptions.ASYNC_COMPACTION, true);

// When enabled, compaction runs in background
// Operations continue without waiting for compaction to complete
memorySet.add("New item");  // If capacity reached, compaction runs async
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
When async compaction is enabled, compaction runs in a background thread. If compaction fails, errors are logged but don't cause the Flink job to fail.
{{< /hint >}}

## Usage Examples

### Example 1: Conversation History with Semantic Search

Store conversation history and retrieve relevant past conversations:

{{< tabs "Conversation History Example" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.events.event import InputEvent
from flink_agents.api.memory.long_term_memory import SummarizationStrategy
from flink_agents.api.runner_context import RunnerContext

@action(InputEvent)
def handle_user_message(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    
    # Get or create conversation memory set
    conversations = ltm.get_or_create_memory_set(
        name="user_conversations",
        item_type=str,
        capacity=50,
        compaction_strategy=SummarizationStrategy(
            model="my_chat_model",
            limit=5
        )
    )
    
    # Store the current conversation
    user_message = str(event.input)
    conversations.add(user_message)
    
    # Search for relevant past conversations
    relevant_context = conversations.search(
        query=user_message,
        limit=3
    )
    
    # Use context for generating response
    context_messages = [item.value for item in relevant_context]
    # ... generate response using context ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void handleUserMessage(InputEvent event, RunnerContext ctx) throws Exception {
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    
    // Get or create conversation memory set
    MemorySet conversations = ltm.getOrCreateMemorySet(
        "user_conversations",
        String.class,
        50,
        new SummarizationStrategy("my_chat_model", 5)
    );
    
    // Store the current conversation
    String userMessage = event.getInput().toString();
    conversations.add(userMessage);
    
    // Search for relevant past conversations
    List<MemorySetItem> relevantContext = conversations.search(
        userMessage,
        3  // limit
    );
    
    // Use context for generating response
    List<String> contextMessages = relevantContext.stream()
        .map(MemorySetItem::getValue)
        .collect(Collectors.toList());
    // ... generate response using context ...
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 2: Knowledge Base for RAG

Build a searchable knowledge base:

{{< tabs "Knowledge Base Example" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def build_knowledge_base(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    
    # Create knowledge base memory set
    knowledge_base = ltm.get_or_create_memory_set(
        name="product_knowledge",
        item_type=str,
        capacity=1000,
        compaction_strategy=SummarizationStrategy(
            model="my_chat_model",
            limit=10
        )
    )
    
    # Add knowledge documents
    documents = [
        "Product A is designed for enterprise use...",
        "Product B features advanced analytics...",
        # ... more documents
    ]
    
    knowledge_base.add(documents)

@action(InputEvent)
def query_knowledge_base(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    knowledge_base = ltm.get_memory_set("product_knowledge")
    
    # Search knowledge base
    user_query = str(event.input)
    relevant_docs = knowledge_base.search(
        query=user_query,
        limit=5
    )
    
    # Use documents for RAG
    context = "\n".join([item.value for item in relevant_docs])
    # ... use context with LLM ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void buildKnowledgeBase(InputEvent event, RunnerContext ctx) throws Exception {
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    
    // Create knowledge base memory set
    MemorySet knowledgeBase = ltm.getOrCreateMemorySet(
        "product_knowledge",
        String.class,
        1000,
        new SummarizationStrategy("my_chat_model", 10)
    );
    
    // Add knowledge documents
    List<String> documents = Arrays.asList(
        "Product A is designed for enterprise use...",
        "Product B features advanced analytics..."
        // ... more documents
    );
    
    knowledgeBase.add(documents);
}

@Action(listenEvents = {InputEvent.class})
public static void queryKnowledgeBase(InputEvent event, RunnerContext ctx) throws Exception {
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    MemorySet knowledgeBase = ltm.getMemorySet("product_knowledge");
    
    // Search knowledge base
    String userQuery = event.getInput().toString();
    List<MemorySetItem> relevantDocs = knowledgeBase.search(userQuery, 5);
    
    // Use documents for RAG
    String context = relevantDocs.stream()
        .map(MemorySetItem::getValue)
        .collect(Collectors.joining("\n"));
    // ... use context with LLM ...
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 3: ChatMessage Storage

Store and retrieve ChatMessage objects:

{{< tabs "ChatMessage Example" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import ChatMessage, MessageRole

@action(InputEvent)
def store_chat_history(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    
    # Create memory set for ChatMessage items
    chat_history = ltm.get_or_create_memory_set(
        name="chat_history",
        item_type=ChatMessage,
        capacity=100,
        compaction_strategy=SummarizationStrategy(
            model="my_chat_model",
            limit=1
        )
    )
    
    # Store chat messages
    user_message = ChatMessage(
        role=MessageRole.USER,
        content=str(event.input)
    )
    assistant_message = ChatMessage(
        role=MessageRole.ASSISTANT,
        content="Response content here"
    )
    
    chat_history.add([user_message, assistant_message])
    
    # Search chat history
    results = chat_history.search(
        query="What did we discuss about pricing?",
        limit=5
    )
    
    # Retrieve messages
    for item in results:
        message: ChatMessage = item.value
        print(f"{message.role}: {message.content}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat_message.ChatMessage;
import org.apache.flink.agents.api.chat_message.MessageRole;

@Action(listenEvents = {InputEvent.class})
public static void storeChatHistory(InputEvent event, RunnerContext ctx) throws Exception {
    BaseLongTermMemory ltm = ctx.getLongTermMemory();
    
    // Create memory set for ChatMessage items
    MemorySet chatHistory = ltm.getOrCreateMemorySet(
        "chat_history",
        ChatMessage.class,
        100,
        new SummarizationStrategy("my_chat_model", 1)
    );
    
    // Store chat messages
    ChatMessage userMessage = new ChatMessage(
        MessageRole.USER,
        event.getInput().toString()
    );
    ChatMessage assistantMessage = new ChatMessage(
        MessageRole.ASSISTANT,
        "Response content here"
    );
    
    chatHistory.add(Arrays.asList(userMessage, assistantMessage));
    
    // Search chat history
    List<MemorySetItem> results = chatHistory.search(
        "What did we discuss about pricing?",
        5
    );
    
    // Retrieve messages
    for (MemorySetItem item : results) {
        ChatMessage message = (ChatMessage) item.getValue();
        System.out.println(message.getRole() + ": " + message.getContent());
    }
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 4: Async Operations

Use async execution for long-term memory operations:

{{< tabs "Async Operations Example" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def async_memory_operations(event: InputEvent, ctx: RunnerContext) -> None:
    ltm = ctx.long_term_memory
    memory_set = ltm.get_memory_set("my_memory_set")
    
    # Execute async operations using ctx.execute_async
    # This is useful for non-blocking memory operations
    yield from ctx.execute_async(memory_set.add, items=str(event.input))
    
    # Continue processing while add operation completes
    # ... other operations ...
    
    # Get results (if needed)
    yield from ctx.execute_async(memory_set.get)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Note: Java async execution patterns may differ
// Check Java API documentation for async support
```
{{< /tab >}}

{{< /tabs >}}

## Comparison with Other Memory Types

Flink Agents provides three types of memory, each suited for different use cases:

| Feature | Sensory Memory | Short-Term Memory | Long-Term Memory |
|---------|----------------|-------------------|------------------|
| **Retention** | Single Run | Multiple Runs | Multiple Runs |
| **Visibility** | Single-Key | Single-Key | Single-Key |
| **Accuracy** | Precise | Precise | Vague (supports compaction) |
| **Storage** | Flink MapState | Flink MapState | Vector Store |
| **Search** | No | No | Semantic Search |
| **Capacity** | Limited by Flink state | Limited by Flink state | Configurable (with compaction) |
| **Use Case** | Temporary data | Small persistent data | Large searchable data |
| **Item Types** | Any primitive/object | Any primitive/object | String, ChatMessage |

### Decision Guide

**Use Long-Term Memory when:**
- You need to store large amounts of data
- Semantic search is required
- Data can be summarized/compacted
- Working with documents or conversation history
- Building RAG applications

**Use Short-Term Memory when:**
- Storing small amounts of precise data
- Fast, exact retrieval is needed
- Data size is manageable in Flink state
- No semantic search needed

**Use Sensory Memory when:**
- Data is only needed during a single run
- Temporary storage for intermediate results
- Tool call context

## Best Practices

### Recommended Use Cases

1. **Conversation History**: Store and search through past conversations
2. **Knowledge Bases**: Build searchable knowledge bases for RAG
3. **Document Storage**: Store and retrieve large document collections
4. **Experience Learning**: Store agent experiences for future reference

### Capacity Planning

- **Set Appropriate Capacity**: Balance between compaction frequency and memory usage
  - Too small: Frequent compaction, more summarization overhead
  - Too large: More memory usage, less frequent compaction
- **Monitor Size**: Check `memory_set.size` to understand growth patterns
- **Adjust Based on Usage**: Increase capacity if compaction happens too frequently

### Compaction Strategy

- **Summarization Limit**: Set `limit` based on your use case
  - `limit=1`: Single summary of all items (most aggressive)
  - Higher limits: Multiple topic summaries (preserves more detail)
- **Custom Prompts**: Provide custom prompts for better summarization quality
- **Async Compaction**: Enable for better performance in production

### Search Optimization

- **Limit Results**: Use appropriate `limit` values (typically 3-10)
- **Query Quality**: Write clear, specific queries for better results
- **Metadata**: Use `additional_metadata` to store searchable information

### Performance Considerations

- **Async Compaction**: Enable `ASYNC_COMPACTION` to avoid blocking operations
- **Vector Store Performance**: Choose appropriate vector store backend
- **Embedding Model**: Select efficient embedding models for your use case
- **Batch Operations**: Add multiple items at once when possible

### Tips

1. **Memory Set Naming**: Use descriptive names for memory sets
   ```python
   # Good
   memory_set = ltm.get_or_create_memory_set(name="user_conversations", ...)
   
   # Avoid
   memory_set = ltm.get_or_create_memory_set(name="data", ...)
   ```

2. **Item Organization**: Create separate memory sets for different types of data
   ```python
   conversations = ltm.get_or_create_memory_set(name="conversations", ...)
   knowledge_base = ltm.get_or_create_memory_set(name="knowledge", ...)
   ```

3. **Error Handling**: Handle cases where memory sets don't exist
   ```python
   try:
       memory_set = ltm.get_memory_set("my_set")
   except Exception:
       # Handle missing memory set
       memory_set = ltm.get_or_create_memory_set("my_set", ...)
   ```

4. **Monitor Compaction**: Check `compacted` flag in items to understand what's been summarized
   ```python
   for item in memory_set.get():
       if item.compacted:
           print(f"Item {item.id} is a summary")
           # Access original time range if needed
           if isinstance(item.created_time, DatetimeRange):
               print(f"Summarizes items from {item.created_time.start} to {item.created_time.end}")
   ```

## Limitations and Future Work

### Current Limitations

- **Item Types**: Currently supports only `str` and `ChatMessage` (extendable to JSON-serializable objects in future)
- **Compaction Strategies**: Only summarization strategy is implemented (trim strategy planned)
- **Recent Items**: `get_recent(n)` method not yet implemented
- **Cross-Key Visibility**: Memory sets are scoped to single keys

### Future Enhancements

- **Trim Strategy**: Simple removal of oldest items (planned)
- **User-Defined Strategies**: Custom compaction strategies (planned)
- **Local + Remote Storage**: Hot cache with remote cold storage (planned)
- **Extended Item Types**: Support for any JSON-serializable objects (planned)

## Related Documentation

- [Vector Stores]({{< ref "docs/development/vector_stores" >}}) - Vector store setup and configuration
- [Sensory Memory]({{< ref "docs/development/sensory_memory" >}}) - Temporary single-run memory
- [Comprehensive Memory Management Discussion](https://github.com/apache/flink-agents/discussions/339) - Design proposal for memory management

