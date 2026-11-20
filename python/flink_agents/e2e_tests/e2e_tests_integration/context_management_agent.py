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
"""Agent whose chat model looks up accounts one tool call per round.

Each lookup returns a large result, and the model then answers with the sum of the
amounts it can see. No credentials: the chat connection is scripted from the messages
it receives.

The connection also writes the notes when it is asked for a summary: one line for
each round it is shown, with the amount that round found. The sum is therefore only
complete when what context management removed from the model input is still covered
by a summary.

The job's configuration declares the default policy. A request whose text starts with
``masked`` carries its own policy, which only keeps the tool results of the last
round; one whose text starts with ``unmanaged`` carries a disabled policy; and one
whose text starts with ``called`` is made with ``ctx.chat`` and the masking policy.
"""

import re
from typing import Any, Dict, Sequence

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_management_event import ContextManagementEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext

_AMOUNT = re.compile(r"account=(\d+) amount=(\d+)")
_WANTED = re.compile(r"sum (\d+) accounts")
_SUMMARIZED = re.compile(r"\(rounds 1 to (\d+)\)")
_ROUND = re.compile(r"^Round (\d+):\n(.*)$", re.MULTILINE)
_SUMMARIZER_OPENING = "You maintain the working notes"

MASKING = ContextManagementPolicy(tool_result_keep_last_rounds=1)


def lookup_result(account: int) -> str:
    """Return the result of one lookup.

    The amount comes first, then 3000 characters the answer never needs.
    """
    return f"account={account} amount={account * 10} audit=" + "x" * 3000


def _amounts(text: str) -> Dict[str, int]:
    """Return every amount named in a text, by account, in order of first mention."""
    amounts: Dict[str, int] = {}
    for account, amount in _AMOUNT.findall(text):
        amounts.setdefault(account, int(amount))
    return amounts


class ScriptedChatConnection(BaseChatModelConnection):
    """The scripted provider, see the module comment."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: Any = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Call the lookup tool until every account was looked up, then answer.

        The answer reports what the final request showed the model: the sum and the
        number of the amounts it can see, and the number and the characters of the
        tool results in the request.
        """
        self._reject_unsupported_output_schema(output_schema)
        if messages[0].role == MessageRole.SYSTEM and messages[0].text.startswith(
            _SUMMARIZER_OPENING
        ):
            return self._write_notes(messages[1].text, tools)

        request_text = messages[0].blocks[0].text
        visible = []
        results = 0
        result_chars = 0
        rounds = 0
        for message in messages:
            visible.append(message.text)
            if message.tool_calls:
                rounds += 1
            for block in message.blocks:
                if isinstance(block, ToolResultBlock):
                    results += 1
                    result_chars += len(block.text)
                    visible.append(block.text)
        text = "\n".join(visible)
        # Rounds a summary stands for are no longer in the messages, but they were
        # made.
        summarized = _SUMMARIZED.search(text)
        if summarized:
            rounds += int(summarized.group(1))

        wanted = _WANTED.search(request_text)
        if wanted is None:
            msg = f"unexpected request: {request_text}"
            raise ValueError(msg)
        if rounds < int(wanted.group(1)):
            account = rounds + 1
            return ChatResult(
                message=ChatMessage.assistant(
                    [
                        ToolCallBlock(
                            call_id=f"call-{account}",
                            name="lookup",
                            input={"account": account},
                        )
                    ]
                )
            )
        amounts = _amounts(text)
        return ChatResult(
            message=ChatMessage.assistant(
                f"sum={sum(amounts.values())} accounts={len(amounts)} "
                f"results={results} result_chars={result_chars}"
            )
        )

    @staticmethod
    def _write_notes(request: str, tools: Any) -> ChatResult:
        if tools:
            msg = "a summary request must not bind tools"
            raise ValueError(msg)
        # One line for each round the request shows: the amount that round found.
        notes = ""
        for number, text in _ROUND.findall(request):
            amount = _AMOUNT.search(text)
            if amount:
                notes += (
                    f"Round {number}: account={amount.group(1)} "
                    f"amount={amount.group(2)}\n"
                )
        return ChatResult(message=ChatMessage.assistant(notes))


class ScriptedChatModel(BaseChatModelSetup):
    """Chat model setup bound to a ``ScriptedChatConnection``."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return no provider options."""
        return {}


class ContextManagementAgent(Agent):
    """Sends each input to the scripted chat model and emits what it answered."""

    @chat_model_connection
    @staticmethod
    def chat_connection() -> ResourceDescriptor:
        """Declare the scripted connection."""
        return ResourceDescriptor(
            clazz=f"{ScriptedChatConnection.__module__}.{ScriptedChatConnection.__name__}"
        )

    @chat_model_setup
    @staticmethod
    def chat() -> ResourceDescriptor:
        """Declare the scripted chat model with its lookup tool."""
        return ResourceDescriptor(
            clazz=f"{ScriptedChatModel.__module__}.{ScriptedChatModel.__name__}",
            connection="chat_connection",
            model="scripted",
            tools=["lookup"],
        )

    @tool
    @staticmethod
    def lookup(account: int) -> str:
        """Look up the amount held by one account.

        Parameters
        ----------
        account : int
            The account number.
        """
        return lookup_result(account)

    @action(EventType.InputEvent)
    @staticmethod
    async def process_input(event: Event, ctx: RunnerContext) -> None:
        """Send the request, with its own policy when its text asks for one."""
        text = str(InputEvent.from_event(event).input)
        messages = [ChatMessage.user(text)]
        if text.startswith("called"):
            result = await ctx.chat("chat", messages, context_management=MASKING)
            ctx.send_event(OutputEvent(output=f"{text} -> {result.text}"))
            return
        policy = None
        if text.startswith("unmanaged"):
            policy = ContextManagementPolicy.disabled()
        elif text.startswith("masked"):
            policy = MASKING
        ctx.short_term_memory.set("request", text)
        ctx.send_event(
            ChatRequestEvent(model="chat", messages=messages, context_management=policy)
        )

    @action(EventType.ContextManagementEvent)
    @staticmethod
    def record_step(event: Event, ctx: RunnerContext) -> None:
        """Keep the step: context management steps are ordinary events."""
        step = ContextManagementEvent.from_event(event).step
        steps = ctx.short_term_memory.get("steps") or []
        ctx.short_term_memory.set("steps", [*steps, step])

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the answer and the steps reported for the request."""
        response = ChatResponseEvent.from_event(event)
        text = ctx.short_term_memory.get("request")
        if response.is_failed:
            ctx.send_event(OutputEvent(output=f"{text} -> FAILED: {response.error}"))
            return
        steps = ctx.short_term_memory.get("steps") or []
        ctx.send_event(
            OutputEvent(
                output=f"{text} -> {response.response.text} "
                f"steps={','.join(steps) or 'none'}"
            )
        )
