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
"""Functions referenced by ``resources/yaml_test_agent.yaml``.

Each action and tool entry in the YAML points its ``function:`` at one
of the callables in this module by fully-qualified dotted path.
"""

from pydantic import BaseModel
from pyflink.datastream import KeySelector

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


class YamlChatInput(BaseModel):
    """Input record: a question routed to a chat model."""

    id: int
    text: str


class YamlChatOutput(BaseModel):
    """Output record: the chat model's textual answer."""

    id: int
    answer: str


class YamlChatKeySelector(KeySelector):
    """KeySelector partitioning records by their ``id`` attribute.

    Works for both ``YamlChatInput`` (upstream of the math agent) and
    ``YamlChatOutput`` (the math agent's output, piped into a downstream
    agent like ``formatter_agent``).
    """

    def get_key(self, value: "YamlChatInput | YamlChatOutput") -> int:
        """Use the record id as the partition key."""
        return value.id


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b.

    Parameters
    ----------
    a : int
        The first operand
    b : int
        The second operand

    Returns:
    -------
    int:
        The sum of a and b
    """
    return a + b


def query_order(order_id: str, tenant_id: str) -> str:
    """Query an order in the injected tenant.

    Parameters
    ----------
    order_id : str
        The order id requested by the model.
    tenant_id : str
        The tenant id injected by runtime.

    Returns:
    -------
    str:
        The tenant-scoped order identifier.
    """
    return f"yaml-checked:{tenant_id}:{order_id}"


def process_input(event: Event, ctx: RunnerContext) -> None:
    """Route the incoming text to the math or creative chat model.

    The math model has access to the ``add`` tool; the creative model
    does not. Routing is a simple keyword check on the input. The input
    record's ``id`` is stashed in short-term memory so
    ``process_chat_response`` can attach it back to the output.
    """
    data = YamlChatInput.model_validate(InputEvent.from_event(event).input)
    ctx.short_term_memory.set("input_id", data.id)
    lower = data.text.lower()
    model_name = (
        "math_chat_model"
        if ("calculate" in lower or "sum" in lower)
        else "creative_chat_model"
    )
    ctx.send_event(
        ChatRequestEvent(
            model=model_name,
            messages=[ChatMessage(role=MessageRole.USER, content=data.text)],
        )
    )


def chat_request(event: Event, ctx: RunnerContext) -> None:
    """Send the input text to the agent-local ``chat_model``.

    Used by the multi-agent YAML, where each agent declares its own
    ``chat_model`` (math one with the ``add`` tool, creative one
    without) and the action simply forwards the user message.
    """
    data = YamlChatInput.model_validate(InputEvent.from_event(event).input)
    ctx.short_term_memory.set("input_id", data.id)
    ctx.send_event(
        ChatRequestEvent(
            model="chat_model",
            messages=[ChatMessage(role=MessageRole.USER, content=data.text)],
        )
    )


def process_chat_response(event: Event, ctx: RunnerContext) -> None:
    """Emit the model's text response, tagged with the original input id."""
    chat_response = ChatResponseEvent.from_event(event)
    response = chat_response.response
    if not response or not response.content:
        return
    input_id = ctx.short_term_memory.get("input_id")
    ctx.send_event(
        OutputEvent(output=YamlChatOutput(id=input_id, answer=response.content))
    )


def commentary_request(event: Event, ctx: RunnerContext) -> None:
    """Stage-2 action: feed the upstream answer to a second chat model.

    The upstream record is a ``YamlChatOutput`` produced by the math
    agent. We prompt the model to restate the same answer — the test
    only needs the chain to actually pass through stage 2 (verifiable
    by the math digit surviving the second LLM hop). Stashes the id in
    short-term memory so the shared ``process_chat_response`` action
    can re-attach it.
    """
    data = YamlChatOutput.model_validate(InputEvent.from_event(event).input)
    ctx.short_term_memory.set("input_id", data.id)
    ctx.send_event(
        ChatRequestEvent(
            model="chat_model",
            messages=[
                ChatMessage(
                    role=MessageRole.USER,
                    content=(
                        "Here is a math answer from another assistant: "
                        f"{data.answer!r}. Reply with the numeric result only."
                    ),
                )
            ],
        )
    )
