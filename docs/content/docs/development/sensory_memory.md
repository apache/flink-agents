---
title: Sensory Memory
weight: 8
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

Sensory Memory is a temporary storage mechanism in Flink Agents designed for data that only needs to persist during a single agent run. It provides a convenient way to store intermediate results, tool call contexts, and other temporary data without the overhead of persistence across multiple runs.

{{< hint info >}}
Sensory Memory is automatically cleared after each agent run completes, making it ideal for temporary data that doesn't need to persist across runs.
{{< /hint >}}

### Key Characteristics

- **Single-Run Lifecycle**: Data is automatically cleared when the agent run finishes
- **Temporary Storage**: Perfect for intermediate computations and tool call contexts
- **Same API as Short-Term Memory**: Familiar interface for developers already using memory objects
- **Fast Access**: Based on Flink MapState for efficient in-memory operations
- **Nested Objects**: Supports hierarchical data structures via `new_object()`

## When to Use Sensory Memory

Sensory Memory is ideal for:

- **Tool Call Context**: Storing conversation history during multi-turn tool interactions
- **Intermediate Results**: Temporary computation results that don't need persistence
- **Action-to-Action Communication**: Passing data between actions within a single run
- **Temporary State**: Any data that should be discarded after the run completes

{{< hint warning >}}
Do not use Sensory Memory for data that needs to persist across multiple agent runs. Use Short-Term Memory or Long-Term Memory instead.
{{< /hint >}}

## Key Concepts

### Memory Classification

Flink Agents classifies memory types based on three dimensions:

| Dimension | Description |
|-----------|-------------|
| **Visibility** | Scope of memory access (single-key vs cross-key) |
| **Retention** | Lifecycle duration (single run vs multiple runs) |
| **Accuracy** | Information completeness (precise vs vague/compacted) |

Sensory Memory characteristics:

- **Visibility**: Single-key (scoped to the current key)
- **Retention**: Single Run (cleared after run completion)
- **Accuracy**: Precise (exact data retrieval)

### Storage Backend

Sensory Memory is built on Flink MapState, providing:

- Efficient in-memory storage
- Automatic state management
- Integration with Flink's checkpointing (for fault tolerance during execution)
- No persistence overhead after run completion

## Accessing Sensory Memory

Sensory Memory is accessed through the `RunnerContext` object available in action methods:

{{< tabs "Accessing Sensory Memory" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext

@action(InputEvent)
def process_event(event: InputEvent, ctx: RunnerContext) -> None:
    # Access sensory memory
    sensory_mem = ctx.sensory_memory
    
    # Store temporary data
    sensory_mem.set("temp_result", "some_value")
    
    # Retrieve data
    value = sensory_mem.get("temp_result")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.events.Event;

@Action(listenEvents = {InputEvent.class})
public static void processEvent(InputEvent event, RunnerContext ctx) throws Exception {
    // Access sensory memory
    MemoryObject sensoryMem = ctx.getSensoryMemory();
    
    // Store temporary data
    sensoryMem.set("temp_result", "some_value");
    
    // Retrieve data
    MemoryRef ref = sensoryMem.get("temp_result");
    Object value = ref.getValue();
}
```
{{< /tab >}}

{{< /tabs >}}

## Data Structure

Sensory Memory supports a hierarchical key-value structure:

### Supported Value Types

- **Primitive Types**: `str`, `int`, `float`, `bool`, `None`
- **Collections**: `list`, `dict` (maps)
- **Nested Objects**: Created via `new_object()` method

### Path-Based Access

Fields are accessed using dot-separated paths:

```python
# Set a top-level field
ctx.sensory_memory.set("user_id", "12345")

# Set a nested field (creates intermediate objects automatically)
ctx.sensory_memory.set("session.data.timestamp", 1234567890)

# Access nested fields
timestamp = ctx.sensory_memory.get("session.data.timestamp")
```

## Operations

Sensory Memory provides the same operations as Short-Term Memory through the `MemoryObject` interface:

### get()

Retrieve a value or nested object by path or `MemoryRef`.

{{< tabs "get() Method" >}}

{{< tab "Python" >}}
```python
# Get by path string
value = ctx.sensory_memory.get("my_field")

# Get nested object (returns MemoryObject)
session = ctx.sensory_memory.get("session")

# Get by MemoryRef
ref = MemoryRef(memory_type=MemoryType.SENSORY, path="my_field")
value = ctx.sensory_memory.get(ref)

# Returns None if field doesn't exist
missing = ctx.sensory_memory.get("non_existent")  # None
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Get by path string
MemoryRef ref = ctx.getSensoryMemory().get("my_field");
Object value = ref.getValue();

// Get nested object (returns MemoryObject)
MemoryObject session = (MemoryObject) ctx.getSensoryMemory().get("session");

// Returns null if field doesn't exist
MemoryRef missing = ctx.getSensoryMemory().get("non_existent");  // null
```
{{< /tab >}}

{{< /tabs >}}

### set()

Store a value at the specified path. Creates intermediate objects automatically.

{{< tabs "set() Method" >}}

{{< tab "Python" >}}
```python
# Set a simple value
ref = ctx.sensory_memory.set("user_id", "12345")

# Set nested value (creates intermediate objects)
ref = ctx.sensory_memory.set("session.data.timestamp", 1234567890)

# Returns MemoryRef for the stored value
print(ref.path)  # "session.data.timestamp"
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set a simple value
MemoryRef ref = ctx.getSensoryMemory().set("user_id", "12345");

// Set nested value (creates intermediate objects)
MemoryRef nestedRef = ctx.getSensoryMemory().set("session.data.timestamp", 1234567890L);

// Returns MemoryRef for the stored value
System.out.println(nestedRef.getPath());  // "session.data.timestamp"
```
{{< /tab >}}

{{< /tabs >}}

### new_object()

Create a nested object at the specified path.

{{< tabs "new_object() Method" >}}

{{< tab "Python" >}}
```python
# Create a new nested object
session_obj = ctx.sensory_memory.new_object("session")

# Now you can set fields on the nested object
session_obj.set("user_id", "12345")
session_obj.set("timestamp", 1234567890)

# Or set fields using full path
ctx.sensory_memory.set("session.user_id", "12345")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Create a new nested object
MemoryObject sessionObj = ctx.getSensoryMemory().newObject("session");

// Now you can set fields on the nested object
sessionObj.set("user_id", "12345");
sessionObj.set("timestamp", 1234567890L);

// Or set fields using full path
ctx.getSensoryMemory().set("session.user_id", "12345");
```
{{< /tab >}}

{{< /tabs >}}

### is_exist()

Check if a field exists at the given path.

{{< tabs "is_exist() Method" >}}

{{< tab "Python" >}}
```python
# Check if field exists
if ctx.sensory_memory.is_exist("user_id"):
    value = ctx.sensory_memory.get("user_id")

# Check nested paths
exists = ctx.sensory_memory.is_exist("session.data.timestamp")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Check if field exists
if (ctx.getSensoryMemory().isExist("user_id")) {
    MemoryRef ref = ctx.getSensoryMemory().get("user_id");
    Object value = ref.getValue();
}

// Check nested paths
boolean exists = ctx.getSensoryMemory().isExist("session.data.timestamp");
```
{{< /tab >}}

{{< /tabs >}}

### get_field_names()

Get all top-level field names in the current object.

{{< tabs "get_field_names() Method" >}}

{{< tab "Python" >}}
```python
# Get all top-level field names
field_names = ctx.sensory_memory.get_field_names()
# Returns: ["user_id", "session", "temp_data"]

# Get field names from nested object
session = ctx.sensory_memory.get("session")
session_fields = session.get_field_names()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Get all top-level field names
List<String> fieldNames = ctx.getSensoryMemory().getFieldNames();
// Returns: ["user_id", "session", "temp_data"]

// Get field names from nested object
MemoryObject session = (MemoryObject) ctx.getSensoryMemory().get("session");
List<String> sessionFields = session.getFieldNames();
```
{{< /tab >}}

{{< /tabs >}}

### get_fields()

Get all top-level fields and their values.

{{< tabs "get_fields() Method" >}}

{{< tab "Python" >}}
```python
# Get all top-level fields
fields = ctx.sensory_memory.get_fields()
# Returns: {"user_id": "12345", "session": <MemoryObject>, "temp_data": "value"}

# Note: Nested objects are returned as MemoryObject instances
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Get all top-level fields
Map<String, Object> fields = ctx.getSensoryMemory().getFields();
// Returns: {"user_id": "12345", "session": <MemoryObject>, "temp_data": "value"}

// Note: Nested objects are returned as MemoryObject instances
```
{{< /tab >}}

{{< /tabs >}}

## Usage Examples

### Example 1: Tool Call Context

Sensory Memory is commonly used to store tool call context during multi-turn interactions with LLMs:

{{< tabs "Tool Call Context Example" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.runner_context import RunnerContext

@action(ChatRequestEvent)
def handle_chat_request(event: ChatRequestEvent, ctx: RunnerContext) -> None:
    # Store the initial request ID in sensory memory
    ctx.sensory_memory.set("current_request_id", str(event.id))
    
    # Initialize conversation context
    conversation = ctx.sensory_memory.new_object("conversation")
    conversation.set("messages", [])
    
    # Process the chat request...
    # The conversation context will be automatically cleared after the run

@action(ToolResponseEvent)
def handle_tool_response(event: ToolResponseEvent, ctx: RunnerContext) -> None:
    # Retrieve the request ID from sensory memory
    request_id = ctx.sensory_memory.get("current_request_id")
    
    # Access conversation context
    conversation = ctx.sensory_memory.get("conversation")
    messages = conversation.get("messages")
    
    # Add tool response to conversation
    messages.append({"role": "tool", "content": str(event.responses)})
    conversation.set("messages", messages)
    
    # Continue processing...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {ChatRequestEvent.class})
public static void handleChatRequest(ChatRequestEvent event, RunnerContext ctx) throws Exception {
    // Store the initial request ID in sensory memory
    ctx.getSensoryMemory().set("current_request_id", event.getId().toString());
    
    // Initialize conversation context
    MemoryObject conversation = ctx.getSensoryMemory().newObject("conversation");
    conversation.set("messages", new ArrayList<>());
    
    // Process the chat request...
    // The conversation context will be automatically cleared after the run
}

@Action(listenEvents = {ToolResponseEvent.class})
public static void handleToolResponse(ToolResponseEvent event, RunnerContext ctx) throws Exception {
    // Retrieve the request ID from sensory memory
    MemoryRef requestIdRef = ctx.getSensoryMemory().get("current_request_id");
    String requestId = requestIdRef.getValue().toString();
    
    // Access conversation context
    MemoryObject conversation = (MemoryObject) ctx.getSensoryMemory().get("conversation");
    MemoryRef messagesRef = conversation.get("messages");
    List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesRef.getValue();
    
    // Add tool response to conversation
    Map<String, Object> toolMessage = new HashMap<>();
    toolMessage.put("role", "tool");
    toolMessage.put("content", event.getResponses().toString());
    messages.add(toolMessage);
    conversation.set("messages", messages);
    
    // Continue processing...
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 2: Passing Data Between Actions

Use Sensory Memory to pass temporary data between actions within a single run:

{{< tabs "Action-to-Action Communication" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def process_input(event: InputEvent, ctx: RunnerContext) -> None:
    # Perform some computation
    result = perform_computation(event.input)
    
    # Store intermediate result in sensory memory
    ctx.sensory_memory.set("computation_result", result)
    
    # Send event to trigger next action
    ctx.send_event(ProcessedDataEvent(data=result))

@action(ProcessedDataEvent)
def process_data(event: ProcessedDataEvent, ctx: RunnerContext) -> None:
    # Retrieve the intermediate result
    stored_result = ctx.sensory_memory.get("computation_result")
    
    # Use both the event data and stored result
    final_result = combine_results(event.data, stored_result)
    
    # Send output event
    ctx.send_event(OutputEvent(output=final_result))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
    // Perform some computation
    Object result = performComputation(event.getInput());
    
    // Store intermediate result in sensory memory
    ctx.getSensoryMemory().set("computation_result", result);
    
    // Send event to trigger next action
    ctx.sendEvent(new ProcessedDataEvent(result));
}

@Action(listenEvents = {ProcessedDataEvent.class})
public static void processData(ProcessedDataEvent event, RunnerContext ctx) throws Exception {
    // Retrieve the intermediate result
    MemoryRef resultRef = ctx.getSensoryMemory().get("computation_result");
    Object storedResult = resultRef.getValue();
    
    // Use both the event data and stored result
    Object finalResult = combineResults(event.getData(), storedResult);
    
    // Send output event
    ctx.sendEvent(new OutputEvent(finalResult));
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 3: Temporary Computation Results

Store temporary computation results that don't need persistence:

{{< tabs "Temporary Computation Results" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def analyze_data(event: InputEvent, ctx: RunnerContext) -> None:
    # Store temporary analysis results
    analysis = ctx.sensory_memory.new_object("analysis")
    
    # Perform various analyses
    stats = calculate_statistics(event.input)
    analysis.set("statistics", stats)
    
    patterns = detect_patterns(event.input)
    analysis.set("patterns", patterns)
    
    # Use the analysis results
    summary = generate_summary(analysis.get("statistics"), analysis.get("patterns"))
    
    # Results are automatically cleared after the run
    ctx.send_event(OutputEvent(output=summary))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void analyzeData(InputEvent event, RunnerContext ctx) throws Exception {
    // Store temporary analysis results
    MemoryObject analysis = ctx.getSensoryMemory().newObject("analysis");
    
    // Perform various analyses
    Map<String, Object> stats = calculateStatistics(event.getInput());
    analysis.set("statistics", stats);
    
    List<String> patterns = detectPatterns(event.getInput());
    analysis.set("patterns", patterns);
    
    // Use the analysis results
    MemoryRef statsRef = analysis.get("statistics");
    MemoryRef patternsRef = analysis.get("patterns");
    String summary = generateSummary(statsRef.getValue(), patternsRef.getValue());
    
    // Results are automatically cleared after the run
    ctx.sendEvent(new OutputEvent(summary));
}
```
{{< /tab >}}

{{< /tabs >}}

### Example 4: Nested Object Usage

Create and work with nested object structures:

{{< tabs "Nested Objects" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
def process_with_nested_data(event: InputEvent, ctx: RunnerContext) -> None:
    # Create nested structure
    user_session = ctx.sensory_memory.new_object("user_session")
    
    # Set top-level fields
    user_session.set("user_id", "12345")
    user_session.set("start_time", 1234567890)
    
    # Create nested objects
    preferences = user_session.new_object("preferences")
    preferences.set("theme", "dark")
    preferences.set("language", "en")
    
    # Access nested data
    theme = ctx.sensory_memory.get("user_session.preferences.theme")
    # Or through the object
    theme = preferences.get("theme")
    
    # All data is automatically cleared after the run
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void processWithNestedData(InputEvent event, RunnerContext ctx) throws Exception {
    // Create nested structure
    MemoryObject userSession = ctx.getSensoryMemory().newObject("user_session");
    
    // Set top-level fields
    userSession.set("user_id", "12345");
    userSession.set("start_time", 1234567890L);
    
    // Create nested objects
    MemoryObject preferences = userSession.newObject("preferences");
    preferences.set("theme", "dark");
    preferences.set("language", "en");
    
    // Access nested data
    MemoryRef themeRef = ctx.getSensoryMemory().get("user_session.preferences.theme");
    String theme = themeRef.getValue().toString();
    // Or through the object
    MemoryRef themeRef2 = preferences.get("theme");
    
    // All data is automatically cleared after the run
}
```
{{< /tab >}}

{{< /tabs >}}

## Auto-Cleanup Behavior

Sensory Memory is automatically cleared by the framework after each agent run completes. This cleanup happens:

- **When**: After the agent run finishes processing all events
- **What**: All data stored in sensory memory is cleared
- **Why**: Ensures no temporary data persists unnecessarily
- **Framework Responsibility**: The framework handles cleanup automatically; no user action required

{{< hint info >}}
During execution, sensory memory data is checkpointed by Flink for fault tolerance. However, once the run completes, all sensory memory is cleared and will not be available in subsequent runs.
{{< /hint >}}

### Manual Cleanup

While the framework handles cleanup automatically, you can also manually clear sensory memory if needed:

{{< tabs "Manual Cleanup" >}}

{{< tab "Python" >}}
```python
# Note: Manual cleanup is rarely needed as framework handles it automatically
# This is mainly for local testing scenarios
from flink_agents.runtime.local_runner import LocalRunnerContext

if isinstance(ctx, LocalRunnerContext):
    ctx.clear_sensory_memory()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Note: Manual cleanup is rarely needed as framework handles it automatically
// This is mainly for testing scenarios
if (ctx instanceof LocalRunnerContext) {
    ((LocalRunnerContext) ctx).clearSensoryMemory();
}
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
| **Auto-Cleanup** | Yes (after run) | No | No (manual management) |
| **Use Case** | Temporary data, tool context | Small persistent data | Large semantic search data |
| **Capacity** | Limited by Flink state | Limited by Flink state | Configurable capacity |

### Decision Guide

**Use Sensory Memory when:**
- Data is only needed during a single agent run
- Storing tool call context or conversation history
- Passing temporary data between actions
- Storing intermediate computation results

**Use Short-Term Memory when:**
- Data needs to persist across multiple runs
- Storing small amounts of precise data
- Need fast, precise retrieval
- Data size is manageable in Flink state

**Use Long-Term Memory when:**
- Storing large amounts of data
- Need semantic search capabilities
- Data can be compressed/summarized
- Working with documents or embeddings

## Best Practices

### Recommended Use Cases

1. **Tool Call Context**: Store conversation history during multi-turn tool interactions
2. **Intermediate Results**: Temporary computation results within a single run
3. **Action Coordination**: Pass data between actions that execute in sequence
4. **Temporary State**: Any state that should be discarded after run completion

### Anti-Patterns to Avoid

{{< hint warning >}}
**Don't use Sensory Memory for persistent data**: If you need data to survive across runs, use Short-Term Memory or Long-Term Memory instead.
{{< /hint >}}

1. **Persistent User Data**: Don't store user preferences, session data, or other persistent information
2. **Cross-Run State**: Don't rely on sensory memory for state that needs to persist
3. **Large Data Sets**: For large data, consider Long-Term Memory with compaction
4. **Critical Data**: Don't store critical data that must survive failures in sensory memory

### Performance Considerations

- **Efficient Access**: Sensory memory provides fast in-memory access
- **No Persistence Overhead**: Since data is cleared after runs, there's no persistence cost
- **State Size**: Keep sensory memory size reasonable to avoid excessive Flink state overhead
- **Checkpointing**: During execution, sensory memory is checkpointed for fault tolerance

### Tips

1. **Use Descriptive Paths**: Use clear, hierarchical paths for better organization
   ```python
   # Good
   ctx.sensory_memory.set("tool_call.session.conversation", messages)
   
   # Avoid
   ctx.sensory_memory.set("data", messages)
   ```

2. **Leverage Nested Objects**: Use `new_object()` for complex data structures
   ```python
   session = ctx.sensory_memory.new_object("session")
   session.set("user_id", "12345")
   session.set("timestamp", 1234567890)
   ```

3. **Check Before Access**: Use `is_exist()` to check field existence before accessing
   ```python
   if ctx.sensory_memory.is_exist("result"):
       result = ctx.sensory_memory.get("result")
   ```

4. **Clear After Use**: While not required (framework handles it), you can clear specific fields if needed
   ```python
   # Note: Framework clears everything after run, but you can clear specific fields
   # if you need to free memory during a long-running process
   ```

## Related Documentation

- [Comprehensive Memory Management Discussion](https://github.com/apache/flink-agents/discussions/339) - Design proposal for memory management

