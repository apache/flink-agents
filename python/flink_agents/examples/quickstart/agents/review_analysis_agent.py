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
from typing import Any, Dict, Tuple, Type

from pydantic import BaseModel

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelSetup,
)
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    prompt,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.runner_context import RunnerContext
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelSetup,
)


class ProductReview(BaseModel):
    """Data model representing a product review.

    Attributes:
    ----------
    id : str
        The unique identifier for the product being reviewed.
    review : str
        The review of the product.
    """

    id: str
    review: str


class ProductReviewAnalysisRes(BaseModel):
    """Data model representing analysis result of a product review.

    Attributes:
    ----------
    id : str
        The unique identifier for the product being reviewed.
    score : int
        The satisfaction score given by the reviewer.
    reasons : List[str]
        A list of reasons provided by the reviewer for dissatisfaction, if any.
    """

    id: str
    score: int
    reasons: list[str]


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
        return Prompt.from_text(prompt_str)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on review analysis."""
        return OllamaChatModelSetup, {
            "connection": "ollama_server",
            "prompt": "review_analysis_prompt",
            "extract_reasoning": True,
        }

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
