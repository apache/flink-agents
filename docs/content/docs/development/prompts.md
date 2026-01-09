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

{{< hint warning >}}
MCP Prompt is only supported in python currently.
{{< /hint >}}
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
        new Prompt.fromMessages(
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
            clazz=OllamaChatModelSetup,
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
        return new org.apache.flink.agents.api.prompt.Prompt(
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
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
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

## MCP Prompt

{{< hint info >}}
MCP (Model Context Protocol) is a standardized protocol for integrating AI applications with external data sources and tools. MCP prompts allow dynamic prompt retrieval from MCP servers.
{{< /hint >}}

{{< hint warning >}}
**JDK Requirement (Java API only):** If you are using the **Java API** to develop Flink Agents jobs with MCP, you need **JDK 17 or higher**. This requirement does not apply to **Python API** users - the Python SDK has its own MCP implementation and works with JDK 11+.
{{< /hint >}}

MCP prompts are managed by external MCP servers and automatically discovered when you define an MCP server connection in your agent.

### Define MCP Server with Prompts

Create an MCP server that exposes prompts using the `FastMCP` library:

```python
# mcp_server.py
mcp = FastMCP("ReviewServer")

@mcp.prompt()
def review_analysis_prompt(product_id: str, review: str) -> str:
    """Prompt for analyzing product reviews."""
    return f"""
    Analyze the following product review and provide a satisfaction score (1-5).

    Product ID: {product_id}
    Review: {review}

    Output format: {{"score": 1-5, "reasons": ["reason1", "reason2"]}}
    """

mcp.run("streamable-http")
```

**Key points:**
- Use `@mcp.prompt()` decorator to define prompts
- Prompt function parameters become template variables
- The function name becomes the prompt identifier

### Use MCP Prompts in Agent

Connect to the MCP server and use its prompts in your agent:

{{< tabs "Use MCP Prompts in Agent" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):

    @mcp_server
    @staticmethod
    def review_mcp_server() -> MCPServer:
        """Connect to MCP server."""
        return MCPServer(endpoint="http://127.0.0.1:8000/mcp")

    @chat_model_connection
    @staticmethod
    def ollama_server() -> ResourceDescriptor:
        """Ollama connection."""
        return ResourceDescriptor(clazz=OllamaChatModelConnection)

    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",  # Reference MCP prompt by name
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        input_data = event.input

        # Provide prompt variables via extra_args
        msg = ChatMessage(
            role=MessageRole.USER,
            extra_args={
                "product_id": input_data.product_id,
                "review": input_data.review
            }
        )
        ctx.send_event(ChatRequestEvent(model="review_model", messages=[msg]))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {

    @MCPServer
    public static org.apache.flink.agents.integrations.mcp.MCPServer reviewMcpServer() {
        return org.apache.flink.agents.integrations.mcp.MCPServer
                .builder("http://127.0.0.1:8000/mcp")
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    @ChatModelConnection
    public static ResourceDescriptor ollamaServer() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaServer")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "reviewAnalysisPrompt") // Reference MCP prompt by name
                .build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        CustomTypesAndResources.ProductReview inputData =
                (CustomTypesAndResources.ProductReview) event.getInput();

        // Provide prompt variables via extra_args
        ChatMessage msg = new ChatMessage(
                MessageRole.USER,
                "",
                Map.of(
                        "product_id", inputData.getProductId(),
                        "review", inputData.getReview()
                )
        );
        ctx.sendEvent(new ChatRequestEvent("reviewModel", List.of(msg)));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- In Python, use `@mcp_server` decorator to define MCP server connection
- In Java, use `@MCPServer` annotation to define MCP server connection
- Use the builder pattern in Java to configure the MCP server with endpoint, timeout, headers, and authentication
- Reference MCP prompts by their function name (e.g., `"review_analysis_prompt"` in Python, `"reviewAnalysisPrompt"` in Java)
- Provide prompt parameters using `ChatMessage.extra_args` (Python) or the third parameter of `ChatMessage` constructor (Java)
- All prompts and tools from the MCP server are automatically registered