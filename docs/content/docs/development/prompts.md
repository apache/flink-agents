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

## Local Prompt

### Creating from Text

The simplest way to create a prompt is from a text string using `Prompt.from_text()`:

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

**Key points:**
- Use `{variable_name}` for template variables that will be substituted at runtime
- Escape literal braces by doubling them: `{{` and `}}`

### Creating from Messages

For more control, create prompts from a sequence of `ChatMessage` objects using `Prompt.from_messages()`:

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

**Key points:**
- Define multiple messages with different roles (SYSTEM, USER)
- Each message can have its own template variables

### Using Prompts in Agents

Register a prompt as an agent resource using the `@prompt` decorator:

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

Prompts use `{variable_name}` syntax for template variables. Variables are filled from `ChatMessage.extra_args`. The prompt is automatically applied when the chat model is invoked.

## MCP Prompt

{{< hint info >}}
MCP (Model Context Protocol) is a standardized protocol for integrating AI applications with external data sources and tools. MCP prompts allow dynamic prompt retrieval from MCP servers.
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

**Key points:**
- Use `@mcp_server` decorator to define MCP server connection
- Reference MCP prompts by their function name (e.g., `"review_analysis_prompt"`)
- Provide prompt parameters using `ChatMessage.extra_args`
- All prompts and tools from the MCP server are automatically registered