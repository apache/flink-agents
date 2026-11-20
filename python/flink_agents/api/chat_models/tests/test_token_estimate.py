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
    provider_instructions,
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


@pytest.mark.parametrize("tokens", [float("nan"), 12.0, 12.5, "12", None, True])
def test_a_count_that_is_not_an_integer_is_rejected(tokens: object) -> None:
    # The count is a whole number of tokens, as it is on the Java side. A count that
    # is not a number would pass every comparison with a trigger.
    with pytest.raises(TypeError, match="tokens must be an integer"):
        TokenEstimate.approximate(tokens)
    with pytest.raises(TypeError, match="tokens must be an integer"):
        TokenEstimate.exact(tokens)


@pytest.mark.parametrize("flag", ["false", "true", 0, 1, 0.0, None])
def test_a_flag_that_is_not_a_boolean_is_rejected(flag: object) -> None:
    # A text such as "false" would count as true where an estimate is corrected, and
    # would be reported as it is.
    with pytest.raises(TypeError, match="is_approximate must be a bool"):
        TokenEstimate(tokens=12, is_approximate=flag)


def test_an_empty_request_costs_nothing() -> None:
    assert _approximate([]) == 0
    # No list of tools is no tools, as on the Java side.
    assert approximate_token_estimate([], None) == TokenEstimate.approximate(0)


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


@pytest.mark.parametrize(
    "text",
    [
        "a\x7fb",
        "a\x80b",
        "caf\u00e9 au lait",
        "\u6f22\u5b57 kanji",
        "\U0001f600 and \U0010ffff",
        "\ud83d half of a pair \udc00",
        "\x00 nul \uffff",
        ("ascii " * 50 + "\u00a0") * 40,
    ],
)
def test_a_text_with_other_characters_is_counted_character_by_character(
    text: str,
) -> None:
    # One unit for an ASCII character, DEL included, and four for any other: a
    # control character above ASCII, a character outside the basic plane or half of a
    # surrogate pair for example.
    units = sum(1 if ord(char) < 128 else 4 for char in text)

    assert _approximate([ChatMessage.user(text)]) == 4 + -(-units // 4)


def test_a_tool_call_costs_its_name_and_the_structure_of_its_arguments() -> None:
    call = ChatMessage.assistant(
        [ToolCallBlock(call_id="call-1", name="lookup", input={"q": "x", "n": 3})]
    )
    # name 6; dict 2 + (1 + 4 + 3 + 1) + (1 + 4 + 8 + 1) = 25; 31 units are 8 tokens.
    assert _approximate([call]) == 12

    nested = ChatMessage.assistant(
        [ToolCallBlock(call_id="call-2", name="t", input={"a": [True, None, 1.5, "é"]})]
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


def test_a_tool_without_metadata_costs_the_flat_amount_of_a_tool() -> None:
    # Such a tool has no name, description or schema that a provider is sent.
    assert _approximate([], [_DescribedTool()]) == 8
    assert _approximate([], [_DescribedTool(), _lookup()]) == 8 + 28


def test_a_tool_schema_is_measured_as_compact_json() -> None:
    # The same numbers as on the Java side, where a schema is a text that can be
    # laid out with spaces: 8 for the tool; 6 + 18 + 25 = 49 units are 13 tokens. A
    # character outside ASCII counts as one, whether a text would escape it or not.
    accented = _DescribedTool(
        metadata=ToolMetadata(
            name="lookup",
            description="Look something up.",
            args_schema={"description": "caf\u00e9"},
        )
    )
    assert _approximate([], [accented]) == 21


def test_instructions_given_to_the_provider_are_counted_like_a_system_message() -> None:
    connection = _CountingConnection()
    messages = [ChatMessage.user("hello")]

    assert connection.estimate_input_tokens(messages, [], model="m") == (
        TokenEstimate.approximate(6)
    )
    # 4 more for the message the provider is sent, and 40 characters are 10 tokens.
    assert connection.estimate_input_tokens(
        messages, [], model="m", instructions="i" * 40
    ) == TokenEstimate.approximate(20)
    assert connection.estimate_input_tokens(
        messages, [], instructions=""
    ) == TokenEstimate.approximate(6)
    assert connection.estimate_input_tokens(
        messages, [], instructions=12
    ) == TokenEstimate.approximate(6)

    # A connection can be given them inside additional_kwargs too. The parameter
    # itself comes first.
    inside = {"additional_kwargs": {"instructions": "i" * 40}}
    assert provider_instructions(inside) == "i" * 40
    assert connection.estimate_input_tokens(
        messages, [], model="m", **inside
    ) == TokenEstimate.approximate(20)
    # With both, the ones inside are written to the request last: they are the
    # ones the provider is given.
    assert provider_instructions({"instructions": "top", **inside}) == "i" * 40
    # Also when they are empty: that clears the parameter, and the provider is given
    # no instructions.
    assert (
        provider_instructions(
            {"instructions": "top", "additional_kwargs": {"instructions": ""}}
        )
        is None
    )
    assert (
        provider_instructions({"instructions": "top", "additional_kwargs": {}}) == "top"
    )
    assert (
        provider_instructions(
            {"instructions": "", "additional_kwargs": {"instructions": ""}}
        )
        is None
    )
    assert provider_instructions({"additional_kwargs": "not a mapping"}) is None
    assert provider_instructions(None) is None

    # The Anthropic API takes its instructions as system: a text, or a list of text
    # blocks. Inside additional_kwargs it is read when there are no instructions.
    assert (
        provider_instructions({"additional_kwargs": {"system": "s" * 40}}) == "s" * 40
    )
    assert connection.estimate_input_tokens(
        messages, [], model="m", additional_kwargs={"system": "s" * 40}
    ) == TokenEstimate.approximate(20)
    blocks = [
        {"type": "text", "text": "first"},
        {"type": "text", "text": "second", "cache_control": {"type": "ephemeral"}},
        {"type": "image"},
        "not a block",
    ]
    assert (
        provider_instructions({"additional_kwargs": {"system": blocks}})
        == "first\nsecond"
    )
    assert (
        provider_instructions(
            {"instructions": "top", "additional_kwargs": {"system": "s"}}
        )
        == "top"
    )
    assert provider_instructions({"additional_kwargs": {"system": []}}) is None
    assert provider_instructions({"system": "outside additional_kwargs"}) is None


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
