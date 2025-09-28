---
title: Workflow Agent
weight: 1
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

A workflow style agent in Flink-Agents is an agent whose reasoning and behavior are organized as a directed workflow of modular steps, called actions, connected by events. This design is inspired by the need to orchestrate complex, multi-stage tasks in a transparent, extensible, and data-centric way, leveraging Apache Flink's streaming architecture.

In Flink-Agents, a workflow agent is defined as a class that inherits from the `Agent` base class. The agent's logic is expressed as a set of actions, each of which is a function decorated with `@action(EventType)`. Actions consume events, perform reasoning or tool calls, and emit new events, which may trigger downstream actions. This event-driven workflow forms a directed cyclic graph of computation, where each node is an action and each edge is an event type.

A workflow agent is well-suited for scenarios where the solution requires explicit orchestration, branching, or multi-step reasoning, such as data enrichment, multi-tool pipelines, or complex business logic.

## Workflow Agent Example

```python
class ReviewAnalysisAgent(Agent):
    """An agent that uses a large language model (LLM) to analyze product reviews
    and generate a satisfaction score and potential reasons for dissatisfaction.

    This agent receives a product review and produces a satisfaction score and a list
    of reasons for dissatisfaction. It handles prompt construction, LLM interaction,
    and output parsing.
    """

    @prompt
    @staticmethod
    def review_analysis_prompt() -> Prompt:
        """Prompt for review analysis."""
        return review_analysis_prompt

    @tool
    @staticmethod
    def notify_shipping_manager(id: str, review: str) -> None:
        """Notify the shipping manager when product received a negative review due to
        shipping damage.

        Parameters
        ----------
        id : str
            The id of the product that received a negative review due to shipping damage
        review: str
            The negative review content
        """
        # reuse the declared function, but for parsing the tool metadata, we write doc
        # string here again.
        notify_shipping_manager(id=id, review=review)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on review analysis."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",
            tools=["notify_shipping_manager"],
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event and send chat request for review analysis."""
        input: ProductReview = event.input
        ctx.short_term_memory.set("id", input.id)

        content = f"""
            "id": {input.id},
            "review": {input.review}
        """
        msg = ChatMessage(role=MessageRole.USER, extra_args={"input": content})
        ctx.send_event(ChatRequestEvent(model="review_analysis_model", messages=[msg]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        try:
            json_content = json.loads(event.response.content)
            ctx.send_event(
                OutputEvent(
                    output=ProductReviewAnalysisRes(
                        id=ctx.short_term_memory.get("id"),
                        score=json_content["score"],
                        reasons=json_content["reasons"],
                    )
                )
            )
        except Exception:
            logging.exception(
                f"Error processing chat response {event.response.content}"
            )

            # To fail the agent, you can raise an exception here.
```

## Action

An action is a piece of code that can be executed. Each action listens to at least one type of event. When an event of the listening type occurs, the action will be triggered. An action can also generate new events, to trigger other actions.


To declare an action in Agent, user can use `@action` to decorate a function of Agent class, and declare the listened event types as decorator parameters. 

The decorated function signature should be `(Event, RunnerContext) -> None`
```python
class ReviewAnalysisAgent(Agent):
    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        # the action logic
```

In the function, user can also send new events, to trigger other actions, or output the data.
```python
@action(InputEvent)
@staticmethod
def process_input(event: InputEvent, ctx: RunnerContext) -> None:
    # send ChatRequestEvent
    ctx.send_event(ChatRequestEvent(model=xxx, messages=xxx))
    # output data to downstream
    ctx.send_event(OutputEvent(output=xxx))
```
## Event
Events are messages passed between actions. Events may carry payloads. A single event may trigger multiple actions if they are all listening to its type.

There are 2 special types of event.
* `InputEvent`: Generated by the framework, carrying an input data record that arrives at the agent. Actions listening to the `InputEvent` will be the entry points of agent.
* `OutputEvent`: The framework will listen to `OutputEvent`, and convert its payload into outputs of the agent. By generating `OutputEvent`, actions can emit output data.

User can define owner event by extends `Event`.
```python
class MyEvent(Event):
    value: Any
```
Then, user can define action listen or send `MyEvent`
> **Note**: To support the Event Log of Flink-Agents, the payload of `Event` should be `BaseModel` serializable. 
## Built-in Events and Actions

There are several built-in `Event` in Flink-Agents:
* `ChatRequestEvent`: Event carries the chat messages to be sent to llm.
* `ChatResponseEvent`: Event carries the llm response.
* `ToolRequestEvent`: Event carries the tool call extracted from llm response.
* `ToolResponseEvent`: Event carries the tool execution result.

Flink-Agents provide two built-in actions to process `ChatRequestEvent`, `ToolRequestEvent` and `ToolResponseEvent`, user only need define actions to send `ChatRequestEvent` and process `ChatResponseEvent`.

## Memory

Memory is data that will be remembered across actions and agent runs.

### Short-Term Memory

Short-Term Memory is shared across all actions within an agent run, and multiple agent runs with the same input key. This corresponds to Flink's Keyed State, which is visible to processing of multiple records with in the same keyed partition, and is transparent to processing of data in other keyed partitions.

User can set and get short-term memory in actions.
```python
@action(InputEvent)
@staticmethod
def process_input(event: InputEvent, ctx: RunnerContext) -> None:
    """Process input event and send chat request for review analysis."""
    ...
    ctx.short_term_memory.set("id", input.id)
    ...

@action(ChatResponseEvent)
@staticmethod
def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
    """Process chat response event and send output event."""
    ...
    id=ctx.short_term_memory.get("id"),
    ...

```
