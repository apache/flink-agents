################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
import json
import logging
from typing import TYPE_CHECKING

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    prompt,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductSuggestion,
    product_suggestion_prompt,
)
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelSetup,
)

if TYPE_CHECKING:
    from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
        ProductReviewSummary,
    )


class ProductSuggestionAgent(Agent):
    """An agent that uses a large language model (LLM) to generate actionable product
    improvement suggestions from aggregated product review data.

    This agent receives a summary of product reviews, including a rating distribution
    and a list of user dissatisfaction reasons, and produces concrete suggestions for
    product enhancement. It handles prompt construction, LLM interaction, and output
    parsing.
    """

    @prompt
    @staticmethod
    def generate_suggestion_prompt() -> Prompt:
        """Generate product suggestions based on the rating distribution and user
        dissatisfaction reasons.
        """
        return product_suggestion_prompt

    @chat_model_setup
    @staticmethod
    def generate_suggestion_model() -> ResourceDescriptor:
        """ChatModel which focus on generating product suggestions."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="generate_suggestion_prompt",
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event."""
        input: ProductReviewSummary = event.input
        ctx.short_term_memory.set("id", input.id)
        ctx.short_term_memory.set("score_hist", input.score_hist)

        content = f"""
            "id": {input.id},
            "score_histogram": {input.score_hist},
            "unsatisfied_reasons": {input.unsatisfied_reasons}
        """
        ctx.send_event(
            ChatRequestEvent(
                model="generate_suggestion_model",
                messages=[
                    ChatMessage(role=MessageRole.USER, extra_args={"input": content})
                ],
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response event."""
        try:
            json_content = json.loads(event.response.content)
            ctx.send_event(
                OutputEvent(
                    output=ProductSuggestion(
                        id=ctx.short_term_memory.get("id"),
                        score_hist=ctx.short_term_memory.get("score_hist"),
                        suggestions=json_content["suggestion_list"],
                    )
                )
            )
        except Exception:
            logging.exception(
                f"Error processing chat response {event.response.content}"
            )

            # To fail the agent, you can raise an exception here.
