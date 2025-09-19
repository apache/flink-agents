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

In Flink-Agents, a workflow agent is defined as a class that inherits from the `Agent` base class. The agent's logic is expressed as a set of actions, each of which is a function decorated with `@action(EventType)`. Actions consume events, perform reasoning or tool calls, and emit new events, which may trigger downstream actions. This event-driven workflow forms a directed acyclic graph (DAG) of computation, where each node is an action and each edge is an event type.

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
        prompt_str = """
    Analyze the user review and product information to determine a
    satisfaction score (1-5) and potential reasons for dissatisfaction.

    Example input format:
    {{
        "id": "12345",
        "review": "The headphones broke after one week of use. Very poor quality."
    }}

    Ensure your response can be parsed by Python JSON, using this format as an example:
    {{
     "score": 1,
     "reasons": [
       "poor quality"
       ]
    }}

    input:
    {input}
    """
        return Prompt.from_text("review_analysis_prompt", prompt_str)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on review analysis."""
        return OllamaChatModelSetup, {
            "name": "review_analysis_model",
            "connection": "ollama_server",
            "prompt": "review_analysis_prompt",
            "extract_reasoning": True,
        }

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event and send chat request for review analysis."""
        input: ProductReview = event.input
        ctx.get_short_term_memory().set("id", input.id)

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
                        id=ctx.get_short_term_memory().get("id"),
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

{{< hint warning >}}
**TODO**: How to define an action.
{{< /hint >}}

## Event

{{< hint warning >}}
**TODO**: How to define, send and handle an event, including InputEvent, OutputEvent, and event between actions.

{{< /hint >}}

## Memory

{{< hint warning >}}
**TODO**: How to use short-term memory and long-term memory.
{{< /hint >}}

