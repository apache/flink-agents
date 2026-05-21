---
title: Event Listener
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

`EventListener` is a callback mechanism that allows you to monitor and react to events as they are processed by the agent. It is triggered at the beginning of event processing, before any actions are executed.

Common use cases include:
- **Monitoring & Metrics**: Tracking event throughput, latency, or specific event types.
- **Logging**: Capturing event details for auditing or debugging.
- **Side Effects**: Triggering external notifications or system updates based on event reception.

## Implementation

You can implement `EventListener` in either Java or Python.

{{< tabs "EventListener Implementation" >}}

{{< tab "Python" >}}
In Python, inherit from the `EventListener` class and implement the `on_event_processed` method.

```python
from flink_agents.api.listener.event_listener import EventListener
from flink_agents.api.event_context import EventContext
from flink_agents.api.events.event import Event

class MyPythonListener(EventListener):
    def on_event_processed(self, context: EventContext, event: Event) -> None:
        print(f"Received event: {event.get_type()} at {context.timestamp}")
```
{{< /tab >}}

{{< tab "Java" >}}
In Java, implement the `EventListener` interface.

```java
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.listener.EventListener;

public class MyJavaListener implements EventListener {
    @Override
    public void onEventProcessed(EventContext context, Event event) {
        System.out.println("Received event: " + event.getType() 
            + " at " + context.getTimestamp());
    }
}
```
{{< /tab >}}

{{< /tabs >}}

## Configuration

Register your listeners in the `AgentPlan` configuration using the `event-listeners` option.

{{< tabs "Configuration" >}}

{{< tab "Python" >}}

```python
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Register listeners using str(ClassName)
agents_env.get_config().set(
    AgentConfigOptions.EVENT_LISTENERS, 
    [str(MyPythonListener)]
)
```
{{< /tab >}}

{{< tab "Java" >}}

```java
AgentsExecutionEnvironment agentsEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Register listeners using fully qualified class names
agentsEnv.getConfig().set(
    AgentConfigOptions.EVENT_LISTENERS, 
    Collections.singletonList(MyJavaListener.class.getName())
);
```
{{< /tab >}}

{{< /tabs >}}

## Best Practices & Limitations

- **Performance**: Listeners are executed **synchronously**. Keep them lightweight and avoid long-running or blocking operations.
- **No-Arg Constructor**: Implementing classes must have a public no-argument constructor for dynamic instantiation.
- **Error Handling**: Implementations must handle their own error recovery. Any unhandled exceptions thrown by a listener will disrupt the main event processing flow and may cause the agent to fail.
- **Cross-Language Support**: If you are using the Java runtime, you can still register Python listeners. The framework will handle the Java-to-Python conversion of `Event` and `EventContext` objects. To optimize performance, this conversion happens only once per event notification, even if multiple Python listeners are registered.
