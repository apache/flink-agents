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
"""The token estimate a chat model reports before a request is sent.

The expected numbers of the approximation are the same as in the Java test
``TokenEstimateTest``; a change to one side has to be made on the other.
"""

from typing import Any, Dict, List, Sequence

import pytest
from pydantic import Field

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_models.token_estimate import (
    TokenEstimate,
    approximate_token_estimate,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType

_LOOKUP_SCHEMA = {"type": "object", "properties": {"q": {"type": "string"}}}


class _DescribedTool(Tool):
    """A tool that is only described, never called."""

    @classmethod
    def tool_type(cls) -> ToolType:
        return ToolType.FUNCTION

    def call(self, *args: Any, **kwargs: Any) -> None:
        raise NotImplementedError


def _lookup() -> _DescribedTool:
    return _DescribedTool(
        metadata=ToolMetadata(
            name="lookup",
            description="Look something up.",
            args_schema=_LOOKUP_SCHEMA,
        )
    )


class _CountingConnection(BaseChatModelConnection):
    """Records what it is asked to estimate and can answer with a count."""

    answer: TokenEstimate | None = None
    estimated_messages: List[ChatMessage] = Field(default_factory=list)
    estimated_tools: List[Tool] = Field(default_factory=list)
    estimated_kwargs: Dict[str, Any] = Field(default_factory=dict)

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        return ChatResult(message=ChatMessage.assistant("ok"))

    def estimate_input_tokens(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> TokenEstimate:
        self.estimated_messages = list(messages)
        self.estimated_tools = list(tools or [])
        self.estimated_kwargs = dict(kwargs)
        if self.answer is None:
            return super().estimate_input_tokens(messages, tools, **kwargs)
        return self.answer


class _Setup(BaseChatModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"model": "m", "temperature": 0.1}


def _approximate(messages: Sequence[ChatMessage], tools: Sequence[Tool] = ()) -> int:
    estimate = approximate_token_estimate(messages, tools)
    assert estimate.is_approximate
    return estimate.tokens


def test_an_estimate_says_whether_it_is_approximate() -> None:
    assert not TokenEstimate.exact(12).is_approximate
    assert TokenEstimate.exact(12).tokens == 12
    assert TokenEstimate.approximate(12).is_approximate
    assert TokenEstimate.approximate(12) != TokenEstimate.exact(12)
    assert TokenEstimate.approximate(12) == TokenEstimate.approximate(12)
    assert str(TokenEstimate.approximate(12)) == "~12 tokens"
    assert str(TokenEstimate.exact(12)) == "12 tokens"
    with pytest.raises(ValueError, match="-1"):
        TokenEstimate.exact(-1)


def test_an_empty_request_costs_nothing() -> None:
    assert _approximate([]) == 0


def test_ascii_text_costs_one_token_per_four_characters_plus_the_message_overhead() -> (
    None
):
    # 4 for the message, 5 characters round up to 2 tokens.
    assert _approximate([ChatMessage.user("hello")]) == 6
    # Two messages: 8, and 4 + 4 characters are 2 tokens.
    assert _approximate([ChatMessage.system("abcd"), ChatMessage.user("efgh")]) == 10


def test_other_characters_cost_one_token_each() -> None:
    # 8 for two messages, 14 ASCII characters and 2 CJK characters at 4 units each.
    assert (
        _approximate([ChatMessage.system("You are terse."), ChatMessage.user("你好")])
        == 14
    )
    # One emoji is one character: 4 units, 1 token.
    assert _approximate([ChatMessage.user("\U0001f600")]) == 5


def test_a_tool_call_costs_its_name_and_the_structure_of_its_arguments() -> None:
    call = ChatMessage.assistant(
        [ToolCallBlock(call_id="call-1", name="lookup", input={"q": "x", "n": 3})]
    )
    # name 6; dict 2 + (1 + 4 + 3 + 1) + (1 + 4 + 8 + 1) = 25; 31 units are 8 tokens.
    assert _approximate([call]) == 12

    nested = ChatMessage.assistant(
        [
            ToolCallBlock(
                call_id="call-2", name="t", input={"a": [True, None, 1.5, "é"]}
            )
        ]
    )
    # name 1; list 2 + 6 + 5 + 9 + 7 = 29; dict 2 + (1 + 4 + 29 + 1) = 37; 38 units.
    assert _approximate([nested]) == 14


def test_a_tool_result_costs_its_text_and_a_flat_amount_per_media_block() -> None:
    result = ChatMessage.tool(
        ToolResultBlock(
            call_id="call-1",
            blocks=[
                TextBlock(text="abcdefgh"),
                ImageBlock.from_url("image/png", "https://example.org/i"),
            ],
        )
    )
    assert _approximate([result]) == 4 + 2 + 1024


def test_media_and_reasoning_blocks_of_a_message_are_counted() -> None:
    message = ChatMessage.user(
        [
            TextBlock(text="abcd"),
            ImageBlock.from_url("image/png", "https://example.org/i"),
        ]
    )
    assert _approximate([message]) == 4 + 1 + 1024

    reasoning = ChatMessage.assistant(
        [ReasoningBlock(text="12345678"), TextBlock(text="abcd")]
    )
    assert _approximate([reasoning]) == 4 + 3


def test_a_tool_definition_costs_its_name_description_and_input_schema() -> None:
    # 8 for the tool; 6 + 18 + 54 = 78 units are 20 tokens. The schema is measured
    # as compact JSON.
    assert _approximate([], [_lookup()]) == 28
    # Units of messages and tools are added before they are rounded up: 81 units.
    assert _approximate([ChatMessage.user("abc")], [_lookup()]) == 4 + 8 + 21


def test_a_connection_approximates_unless_it_overrides_the_estimate() -> None:
    connection = _CountingConnection()

    estimate = connection.estimate_input_tokens([ChatMessage.user("hello")], [])

    assert estimate == TokenEstimate.approximate(6)


def test_the_setup_estimates_the_request_it_would_send() -> None:
    connection = _CountingConnection(answer=TokenEstimate.exact(321))
    lookup = _lookup()
    setup = _Setup(
        connection="c",
        model="m",
        prompt=Prompt.from_text(text="Answer about {topic}."),
    )
    setup._resolved_connection = connection
    setup.tools = [lookup]

    estimate = setup.estimate_input_tokens(
        [ChatMessage.user("hello")], {"topic": "Flink"}
    )

    # The connection decides the count.
    assert estimate == TokenEstimate.exact(321)
    # It is asked about the prepared request: the rendered prompt, then the messages,
    # with the bound tools and the setup's parameters.
    assert [m.text for m in connection.estimated_messages] == [
        "Answer about Flink.",
        "hello",
    ]
    assert connection.estimated_tools == [lookup]
    assert connection.estimated_kwargs["model"] == "m"


def test_the_setup_estimate_falls_back_to_the_approximation() -> None:
    setup = _Setup(connection="c", model="m")
    setup._resolved_connection = _CountingConnection()

    assert setup.estimate_input_tokens(
        [ChatMessage.user("hello")]
    ) == TokenEstimate.approximate(6)


def test_the_setup_estimate_needs_a_resolved_connection() -> None:
    setup = _Setup(connection="c", model="m")

    with pytest.raises(TypeError, match="open\\(\\)"):
        setup.estimate_input_tokens([ChatMessage.user("hello")])
