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

In Flink-Agents, a workflow agent is defined as a class that inherits from the `Agent` base class. The agent's logic is expressed as a set of actions, each of which is a function decorated with `@action(EventType)` in python (or a method annotated with `@action(listenEvents = {})` in java). Actions consume events, perform reasoning or tool calls, and emit new events, which may trigger downstream actions. This event-driven workflow forms a directed cyclic graph of computation, where each node is an action and each edge is an event type.

A workflow agent is well-suited for scenarios where the solution requires explicit orchestration, branching, or multi-step reasoning, such as data enrichment, multi-tool pipelines, or complex business logic.

## Workflow Agent Example

{{< tabs "Workflow Agent Example" >}}

{{< tab "Python" >}}
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
{{< /tab >}}

{{< tab "Java" >}}
```java
/**
 * An agent that uses a large language model (LLM) to analyze product reviews and generate a
 * satisfaction score and potential reasons for dissatisfaction.
 *
 * <p>This agent receives a product review and produces a satisfaction score and a list of reasons
 * for dissatisfaction. It handles prompt construction, LLM interaction, and output parsing.
 */
public class ReviewAnalysisAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt reviewAnalysisPrompt() {
        return REVIEW_ANALYSIS_PROMPT;
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewAnalysisModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "reviewAnalysisPrompt")
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
                .addInitialArgument("extract_reasoning", "true")
                .build();
    }

    /**
     * Tool for notifying the shipping manager when product received a negative review due to
     * shipping damage.
     *
     * @param id The id of the product that received a negative review due to shipping damage
     * @param review The negative review content
     */
    @Tool(
            description =
                    "Notify the shipping manager when product received a negative review due to shipping damage.")
    public static void notifyShippingManager(
            @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
        CustomTypesAndResources.notifyShippingManager(id, review);
    }

    /** Process input event and send chat request for review analysis. */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String input = (String) event.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CustomTypesAndResources.ProductReview inputObj =
                MAPPER.readValue(input, CustomTypesAndResources.ProductReview.class);

        ctx.getShortTermMemory().set("id", inputObj.getId());

        String content =
                String.format(
                        "{\n" + "\"id\": %s,\n" + "\"review\": \"%s\"\n" + "}",
                        inputObj.getId(), inputObj.getReview());
        ChatMessage msg = new ChatMessage(MessageRole.USER, "", Map.of("input", content));

        ctx.sendEvent(new ChatRequestEvent("reviewAnalysisModel", List.of(msg)));
    }

    @Action(listenEvents = ChatResponseEvent.class)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx)
            throws Exception {
        JsonNode jsonNode = MAPPER.readTree(event.getResponse().getContent());
        JsonNode scoreNode = jsonNode.findValue("score");
        JsonNode reasonsNode = jsonNode.findValue("reasons");
        if (scoreNode == null || reasonsNode == null) {
            throw new IllegalStateException(
                    "Invalid response from LLM: missing 'score' or 'reasons' field.");
        }
        List<String> result = new ArrayList<>();
        if (reasonsNode.isArray()) {
            for (JsonNode node : reasonsNode) {
                result.add(node.asText());
            }
        }

        ctx.sendEvent(
                new OutputEvent(
                        new CustomTypesAndResources.ProductReviewAnalysisRes(
                                ctx.getShortTermMemory().get("id").getValue().toString(),
                                scoreNode.asInt(),
                                result)));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

## Action

An action is a piece of code that can be executed. Each action listens to at least one type of event. When an event of the listening type occurs, the action will be triggered. An action can also generate new events, to trigger other actions.


To declare an action in Agent, user can use `@action` to decorate a function of Agent class in python (or annotate a method of Agent class in java), and declare the listened event types as decorator/annotation parameters. 

The decorated/annotated function signature should be `(Event, RunnerContext) -> None`

{{< tabs "Action Function" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):
    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        # the action logic
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {
    /** Process input event and send chat request for review analysis. */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        // the action logic
    }
}
```
{{< /tab >}}

{{< /tabs >}}

In the function, user can also send new events, to trigger other actions, or output the data.

{{< tabs "Send Event" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
@staticmethod
def process_input(event: InputEvent, ctx: RunnerContext) -> None:
    # send ChatRequestEvent
    ctx.send_event(ChatRequestEvent(model=xxx, messages=xxx))
    # output data to downstream
    ctx.send_event(OutputEvent(output=xxx))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
    // send ChatRequestEvent
    ctx.sendEvent(new ChatRequestEvent("my_model", messages));
    // output data to downstream
    ctx.sendEvent(new OutputEvent(xxx));
}   
```
{{< /tab >}}

{{< /tabs >}}

## Event
Events are messages passed between actions. Events may carry payloads. A single event may trigger multiple actions if they are all listening to its type.

There are 2 special types of event.
* `InputEvent`: Generated by the framework, carrying an input data record that arrives at the agent in `input` field . Actions listening to the `InputEvent` will be the entry points of agent.
* `OutputEvent`: The framework will listen to `OutputEvent`, and convert its payload in `output` field into outputs of the agent. By generating `OutputEvent`, actions can emit output data.

User can define owner event by extends `Event`.

{{< tabs "Custom Event" >}}

{{< tab "Python" >}}
```python
class MyEvent(Event):
    value: Any
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyEvent extends Event {
    private Object value;
}
```
{{< /tab >}}

{{< /tabs >}}

Then, user can define actions listen to or send `MyEvent`.

{{< hint info >}}
The payload of python `Event` should be `BaseModel` serializable, of java `Event` should be json serializable.
{{< /hint >}}

## Built-in Events and Actions

There are several built-in `Event` and `Action` in Flink-Agents:
* See [Chat Models]({{< ref "docs/development/chat_models" >}}) for how to chat with a LLM leveraging built-in action and events.
* See [Tool Use]({{< ref "docs/development/tool_use" >}}) for how to programmatically use a tool leveraging built-in action and events.

## Memory

Memory is data that will be remembered across actions and agent runs.

### Short-Term Memory

Short-Term Memory is shared across all actions within an agent run, and multiple agent runs with the same input key. 

Here an *agent run* refers to a complete execution of an agent. Each record from upstream will trigger a new run of agent.

This corresponds to Flink's Keyed State, which is visible to processing of multiple records within the same keyed partition, and is not visible to processing of data in other keyed partitions.

#### Basic Usage

User can set and get short-term memory in actions.

{{< tabs "Basic Usage" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
@staticmethod
def first_action(event: InputEvent, ctx: RunnerContext) -> None:
    ...
    ctx.short_term_memory.set("id", input.id)
    ...

@action(ChatResponseEvent)
@staticmethod
def second_action(event: ChatResponseEvent, ctx: RunnerContext) -> None:
    ...
    id = ctx.short_term_memory.get("id"),
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    ctx.getShortTermMemory().set("id", inputObj.getId());
    ...
}

@Action(listenEvents = {ChatResponseEvent.class})
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    String id = ctx.getShortTermMemory().get("id").getValue();
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Store Nested Object

It's not just a k-v map. User can store nested objects.

{{< tabs "Store Nested Object" >}}

{{< tab "Python" >}}
```python
@action(InputEvent)
@staticmethod
def first_action(event: InputEvent, ctx: RunnerContext) -> None:
    ...
    stm = ctx.short_term_memory
    
    # create nested memory object, and then set the leaf value
    nested_obj = stm.new_object("a")
    nested_obj.set("b", input.id)
    
    # directly set leaf value, will auto crate the nested object    
    stm.set("x.y", input.user)
    ...
    
@action(ChatResponseEvent)
@staticmethod
def second_action(event: InputEvent, ctx: RunnerContext) -> None:
    ...
    stm = ctx.short_term_memory
    
    # directly get leaf value, will auto parse the nested object
    id = stm.get("a.b")
    
    # get the nested object, and then get the leaf value
    nested_obj = stm.get("x")
    user = nested_obj.get("y")
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject stm = ctx.getShortTermMemory();
    
    // create nested memory object, and then set the leaf value
    MemoryObject nestedObj = stm.newObject("a", true);
    nestedObj.set("b", input.getId());
    
    // directly set leaf value, will auto crate the nested object
    stm.set("x.y", input.getUser());
    ...
}

@Action(listenEvents = {ChatResponseEvent.class})
public static void secondAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject stm = ctx.getShortTermMemory();
    
    // directly get leaf value, will auto parse the nested object
    String id = stm.get("a.b").getValue();
    
    // get the nested object, and then get the leaf value
    MemoryObject nestedObj = stm.get("x");
    String user = nestedObj.get("y").getValue();
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Memory Reference

The `set` method of short term memory will return a `MemoryRef`, which can be treated as a reference to the stored object. 

If user want to pass the stored object between actions, they can pass the reference instead, which can reduce the payload size of Event.

{{< tabs "Memory Reference" >}}

{{< tab "Python" >}}
```python
@staticmethod
def first_action(event: Event, ctx: RunnerContext):  # noqa D102
    ...
    stm = ctx.get_short_term_memory()
    
    data_ref = stm.set(data_path, data_to_store)
    ctx.send_event(MyEvent(value=data_ref))
    ...

@action(MyEvent)
@staticmethod
def second_action(event: Event, ctx: RunnerContext):  # noqa D102
    ...
    stm = ctx.get_short_term_memory()
    
    content_ref: MemoryRef = event.value
    processed_data: ProcessedData = stm.get(content_ref)
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(listenEvents = {InputEvent.class})
public static void firstAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject stm = ctx.getShortTermMemory();

    MemoryRef dataRef = stm.set(dataPath, dataToStore);
    ctx.send_event(new MyEvent(dataRef));
    ...
}

@Action(listenEvents = {MyEvent.class})
public static void secondAction(Event event, RunnerContext ctx) throws Exception {
    ...
    MemoryObject stm = ctx.getShortTermMemory();

    MemoryRef contentRef = event.getValue();
    ProcessedData processedData = stm.get(contentRef).getValue();
    ...
}
```
{{< /tab >}}

{{< /tabs >}}
