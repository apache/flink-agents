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
from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import action, chat_model_setup, prompt, skills
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.skills import Skills


class MathAgent(Agent):
    """An agent that answers arithmetic questions using the math-calculator skill.

    Instead of relying on the LLM to compute by itself, this agent exposes a
    ``math-calculator`` skill. When asked to evaluate an expression, the model
    loads the skill (``load_skill``) and follows its instructions to compute the
    result with the ``bc`` calculator through the built-in ``bash`` tool.
    """

    @skills
    @staticmethod
    def my_skills() -> Skills:
        """Declare where to load skills from.

        The skills are bundled under this example package's ``resources/skills``
        directory and loaded by package name, so the agent resolves them the
        same way whether run from the source tree or the installed wheel.
        """
        return Skills.from_package(
            ("flink_agents.examples.quickstart", "resources/skills")
        )

    @prompt
    @staticmethod
    def system_prompt() -> Prompt:
        """System prompt instructing the model to use the skill."""
        return Prompt.from_messages(
            messages=[
                ChatMessage(
                    role=MessageRole.SYSTEM,
                    content="You are a helpful math assistant. Use the "
                    "math-calculator skill when asked to evaluate an expression. "
                    "You must load the skill first and strictly follow its "
                    "instructions. Reply with only the final numeric result.",
                )
            ],
        )

    @chat_model_setup
    @staticmethod
    def math_model() -> ResourceDescriptor:
        """ChatModel with the math-calculator skill enabled."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3.5:9b",
            prompt="system_prompt",
            # Expose the declared skill to this model by name.
            skills=["math-calculator"],
            # Whitelist the shell commands the built-in bash tool may run.
            allowed_commands=["echo", "bc"],
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send a chat request to evaluate the question."""
        question: str = InputEvent.from_event(event).input
        ctx.send_event(
            ChatRequestEvent(
                model="math_model",
                messages=[ChatMessage(role=MessageRole.USER, content=question)],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send the answer as output."""
        chat_response = ChatResponseEvent.from_event(event)
        ctx.send_event(OutputEvent(output=chat_response.response.content))
