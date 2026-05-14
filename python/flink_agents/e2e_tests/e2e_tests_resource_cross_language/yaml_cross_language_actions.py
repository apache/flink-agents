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
"""Python actions for ``resources/yaml_cross_language_agent.yaml``.

The YAML declares a Python Ollama chat model bound to a **Java**
function tool (``calculateBMI`` on the Java cross-language agent).
These actions route input to the math (Java-tool-equipped) or creative
chat model and emit the model's final reply as an ``OutputEvent``.
"""

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def process_input(event: Event, ctx: RunnerContext) -> None:
    """Route the input to math (Java tool) or creative chat model."""
    text = str(InputEvent.from_event(event).input)
    lower = text.lower()
    model_name = (
        "math_chat_model"
        if ("calculate" in lower or "bmi" in lower)
        else "creative_chat_model"
    )
    ctx.send_event(
        ChatRequestEvent(
            model=model_name,
            messages=[ChatMessage(role=MessageRole.USER, content=text)],
        )
    )


def process_chat_response(event: Event, ctx: RunnerContext) -> None:
    """Emit the model's textual response."""
    chat_response = ChatResponseEvent.from_event(event)
    response = chat_response.response
    if response and response.content:
        ctx.send_event(OutputEvent(output=response.content))
