---
title: Prompts
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

# Prompts

## Overview

Prompts are templates that define how your agents communicate with Large Language Models (LLMs). They provide structured instructions, context, and formatting guidelines that shape the LLM's responses. In Flink Agents, prompts are first-class resources that can be defined, reused, and referenced across agents and chat models.

## Prompt Types

Flink Agents supports two types of prompts:

### Local Prompt

Local prompts are templates defined directly in your code. They support variable substitution using `{variable_name}` syntax and can be created from either text strings or message sequences.

### MCP Prompt

MCP (Model Context Protocol) prompts are managed by external MCP servers. They enable dynamic prompt retrieval, centralized prompt management, and integration with external prompt repositories.

See [MCP]({{< ref "docs/development/mcp" >}}) for details.
## Local Prompt

### Creating from Text

The simplest way to create a prompt is from a text string using `Prompt.from_text()`:

{{< tabs "Creating from Text" >}}

{{< tab "Python" >}}
```python
product_suggestion_prompt_str = """
Based on the rating distribution and user dissatisfaction reasons, generate three actionable suggestions for product improvement.

Input format:
{{
    "id": "1",
    "score_histogram": ["10%", "20%", "10%", "15%", "45%"],
    "unsatisfied_reasons": ["reason1", "reason2", "reason3"]
}}

Ensure that your response can be parsed by Python json, use the following format as an example:
{{
    "suggestion_list": [
        "suggestion1",
        "suggestion2",
        "suggestion3"
    ]
}}

input:
{input}
"""

product_suggestion_prompt = Prompt.from_text(product_suggestion_prompt_str)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Prompt for product suggestion agent
String PRODUCT_SUGGESTION_PROMPT_STR =
        "Based on the rating distribution and user dissatisfaction reasons, generate three actionable suggestions for product improvement.\n\n"
                + "Input format:\n"
                + "{\n"
                + "    \"id\": \"1\",\n"
                + "    \"score_histogram\": [\"10%\", \"20%\", \"10%\", \"15%\", \"45%\"],\n"
                + "    \"unsatisfied_reasons\": [\"reason1\", \"reason2\", \"reason3\"]\n"
                + "}\n\n"
                + "Ensure that your response can be parsed by Java JSON, use the following format as an example:\n"
                + "{\n"
                + "    \"suggestion_list\": [\n"
                + "        \"suggestion1\",\n"
                + "        \"suggestion2\",\n"
                + "        \"suggestion3\"\n"
                + "    ]\n"
                + "}\n\n"
                + "input:\n"
                + "{input}";


Prompt productSuggestionPrompt = Prompt.fromText(PRODUCT_SUGGESTION_PROMPT_STR);
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use `{variable_name}` for template variables that will be substituted at runtime
- Escape literal braces by doubling them: `{{` and `}}`

### Creating from Messages

For more control, create prompts from a sequence of `ChatMessage` objects using `Prompt.from_messages()`:

{{< tabs "Creating from Messages" >}}

{{< tab "Python" >}}
```python
review_analysis_prompt = Prompt.from_messages(
    messages=[
        ChatMessage(
            role=MessageRole.SYSTEM,
            content="""
            Analyze the user review and product information to determine a
            satisfaction score (1-5) and potential reasons for dissatisfaction.

            Example input format:
            {{
                "id": "12345",
                "review": "The headphones broke after one week of use."
            }}

            Ensure your response can be parsed by Python JSON:
            {{
                "id": "12345",
                "score": 1,
                "reasons": ["poor quality"]
            }}
            """,
        ),
        ChatMessage(
            role=MessageRole.USER,
            content="""
            "input":
            {input}
            """,
        ),
    ],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
Prompt reviewAnalysisPrompt =
        Prompt.fromMessages(
                Arrays.asList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "Analyze the user review and product information to determine a "
                                        + "satisfaction score (1-5) and potential reasons for dissatisfaction.\n\n"
                                        + "Example input format:\n"
                                        + "{\n"
                                        + "    \"id\": \"12345\",\n"
                                        + "    \"review\": \"The headphones broke after one week of use. Very poor quality.\"\n"
                                        + "}\n\n"
                                        + "Ensure your response can be parsed by Java JSON, using this format as an example:\n"
                                        + "{\n"
                                        + " \"id\": \"12345\",\n"
                                        + " \"score\": 1,\n"
                                        + " \"reasons\": [\n"
                                        + "   \"poor quality\"\n"
                                        + "   ]\n"
                                        + "}"),
                        new ChatMessage(MessageRole.USER, "\"input\":\n" + "{input}")));
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Define multiple messages with different roles (SYSTEM, USER)
- Each message can have its own template variables

### Using Prompts in Agents

Register a prompt as an agent resource using the `@prompt` decorator in python (or `@Prompt` annotation in java):

{{< tabs "Using Prompts in Agents" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):

    @prompt
    @staticmethod
    def review_analysis_prompt() -> Prompt:
        """Prompt for review analysis."""
        return Prompt.from_messages(
            messages=[
                ChatMessage(
                    role=MessageRole.SYSTEM,
                    content="""
            Analyze the user review and product information to determine a
            satisfaction score (1-5) and potential reasons for dissatisfaction.

            Example input format:
            {{
                "id": "12345",
                "review": "The headphones broke after one week of use."
            }}

            Ensure your response can be parsed by Python JSON:
            {{
                "id": "12345",
                "score": 1,
                "reasons": ["poor quality"]
            }}
            """,
                ),
                ChatMessage(
                    role=MessageRole.USER,
                    content="""
            "input":
            {input}
            """,
                ),
            ],
        )

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on review analysis."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",
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
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt reviewAnalysisPrompt() {
        return Prompt.fromMessages(
                Arrays.asList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "Analyze the user review and product information to determine a "
                                        + "satisfaction score (1-5) and potential reasons for dissatisfaction.\n\n"
                                        + "Example input format:\n"
                                        + "{\n"
                                        + "    \"id\": \"12345\",\n"
                                        + "    \"review\": \"The headphones broke after one week of use. Very poor quality.\"\n"
                                        + "}\n\n"
                                        + "Ensure your response can be parsed by Java JSON, using this format as an example:\n"
                                        + "{\n"
                                        + " \"id\": \"12345\",\n"
                                        + " \"score\": 1,\n"
                                        + " \"reasons\": [\n"
                                        + "   \"poor quality\"\n"
                                        + "   ]\n"
                                        + "}"),
                        new ChatMessage(MessageRole.USER, "\"input\":\n" + "{input}")));
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewAnalysisModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "reviewAnalysisPrompt")
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
                .addInitialArgument("extract_reasoning", "true")
                .build();
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
}

```
{{< /tab >}}

{{< /tabs >}}

Prompts use `{variable_name}` syntax for template variables. Variables are filled from `ChatMessage.extra_args`. The prompt is automatically applied when the chat model is invoked.