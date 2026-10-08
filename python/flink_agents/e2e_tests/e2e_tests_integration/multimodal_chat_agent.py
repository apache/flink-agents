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
"""Agent that sends an image arriving with its input to an Ollama vision model."""

import os

from pyflink.common import Row

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, ImageBlock, TextBlock
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext

# A small Ollama vision model; override with OLLAMA_VISION_MODEL.
VISION_MODEL = os.environ.get("OLLAMA_VISION_MODEL") or "qwen3.5:2b"


class MultimodalChatAgent(Agent):
    """Asks a vision model about the Base64 PNG in each input row.

    The image travels the whole agent path: input event, chat request event,
    the built-in chat action and the Ollama connection.
    """

    @chat_model_connection
    @staticmethod
    def vision_connection() -> ResourceDescriptor:
        """Connection to the local Ollama server."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
        )

    @chat_model_setup
    @staticmethod
    def vision_model() -> ResourceDescriptor:
        """The vision model, with thinking off since not every one supports it."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="vision_connection",
            model=os.environ.get("OLLAMA_VISION_MODEL") or VISION_MODEL,
            think=False,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def describe_image(event: Event, ctx: RunnerContext) -> None:
        """Send the input image with a question to the vision model."""
        image = InputEvent.from_event(event).input[0]
        ctx.send_event(
            ChatRequestEvent(
                model="vision_model",
                messages=[
                    ChatMessage.user(
                        [
                            TextBlock(
                                text="What color is this image? Answer in one word."
                            ),
                            ImageBlock.from_base64("image/png", image),
                        ]
                    )
                ],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def output_answer(event: Event, ctx: RunnerContext) -> None:
        """Output the model's answer."""
        answer = ChatResponseEvent.from_event(event).response.text
        ctx.send_event(OutputEvent(output=Row(answer=answer)))
