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
from typing import Any

from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReviewAnalysisRes,
    notify_shipping_manager,
    review_analysis_prompt,
)


class TableKeySelector(KeySelector):
    """KeySelector for extracting key from dictionary input (Table data)."""

    def get_key(self, value: Any) -> str:
        """Extract key from dictionary.

        Parameters
        ----------
        value : Any
            The input value, expected to be a dictionary with 'id' key.

        Returns:
        -------
        str
            The id value as the key.
        """
        if isinstance(value, dict):
            return str(value["id"])
        # Fallback for other types (e.g., Row)
        return str(value["id"]) if hasattr(value, "__getitem__") else str(value.id)


class TableReviewAnalysisAgent(Agent):
    """An agent that analyzes product reviews from Flink Table input.

    This agent is designed to work with Flink Table API. It receives input as
    dictionary (when using from_table()) and produces analysis results including
    satisfaction score and reasons for dissatisfaction.

    The main difference from ReviewAnalysisAgent is that this agent handles
    dictionary input instead of Pydantic objects.
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
        notify_shipping_manager(id=id, review=review)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on review analysis."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",
            tools=["notify_shipping_manager"],
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event from Table data (dictionary format).

        When using from_table(), the input is a dictionary with keys matching
        the table column names.
        """
        # Table input is dictionary format: {"id": "xxx", "review": "xxx", "ts": 123}
        input_dict = event.input
        product_id = str(input_dict["id"])
        review_text = str(input_dict["review"])

        ctx.short_term_memory.set("id", product_id)

        content = f"""
            "id": {product_id},
            "review": {review_text}
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
