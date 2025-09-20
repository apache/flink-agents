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
import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.prompts.prompt import LocalPrompt, Prompt


@pytest.fixture(scope="module")
def text_prompt() -> Prompt:  # noqa: D103
    template = (
        "You ara a product review analyzer, please generate a score and the dislike reasons"
        "(if any) for the review. "
        "The product {product_id} is {description}, and user review is '{review}'."
    )

    return Prompt.from_text(text=template)


def test_prompt_from_text_to_string(text_prompt: LocalPrompt) -> None:  # noqa: D103
    assert text_prompt.format_string(
        product_id="12345",
        description="wireless noise-canceling headphones with 20-hour battery life",
        review="The headphones broke after one week of use. Very poor quality",
    ) == (
        "You ara a product review analyzer, please generate a score and the "
        "dislike reasons(if any) for the review. The product 12345 is wireless "
        "noise-canceling headphones with 20-hour battery life, and user review is "
        "'The headphones broke after one week of use. Very poor quality'."
    )


def test_prompt_from_text_to_messages(text_prompt: LocalPrompt) -> None:  # noqa: D103
    assert text_prompt.format_messages(
        product_id="12345",
        description="wireless noise-canceling headphones with 20-hour battery life",
        review="The headphones broke after one week of use. Very poor quality",
    ) == [
        ChatMessage(
            role=MessageRole.SYSTEM,
            content="You ara a product review analyzer, please generate a score and the "
            "dislike reasons(if any) for the review. The product 12345 is wireless "
            "noise-canceling headphones with 20-hour battery life, and user review is "
            "'The headphones broke after one week of use. Very poor quality'.",
        )
    ]


@pytest.fixture(scope="module")
def messages_prompt() -> Prompt:  # noqa: D103
    template = [
        ChatMessage(
            role=MessageRole.SYSTEM,
            content="You ara a product review analyzer, please generate a score and the dislike reasons"
            "(if any) for the review.",
        ),
        ChatMessage(
            role=MessageRole.USER,
            content="The product {product_id} is {description}, and user review is '{review}'.",
        ),
    ]

    return Prompt.from_messages(messages=template)


def test_prompt_from_messages_to_string(messages_prompt: LocalPrompt) -> None:  # noqa: D103
    assert messages_prompt.format_string(
        product_id="12345",
        description="wireless noise-canceling headphones with 20-hour battery life",
        review="The headphones broke after one week of use. Very poor quality",
    ) == (
        "system: You ara a product review analyzer, please generate a score and the "
        "dislike reasons(if any) for the review.\n"
        "user: The product 12345 is wireless "
        "noise-canceling headphones with 20-hour battery life, and user review is "
        "'The headphones broke after one week of use. Very poor quality'."
    )


def test_prompt_from_messages_to_messages(messages_prompt: LocalPrompt) -> None:  # noqa: D103
    assert messages_prompt.format_messages(
        product_id="12345",
        description="wireless noise-canceling headphones with 20-hour battery life",
        review="The headphones broke after one week of use. Very poor quality",
    ) == [
        ChatMessage(
            role=MessageRole.SYSTEM,
            content="You ara a product review analyzer, please generate a score and the "
            "dislike reasons(if any) for the review.",
        ),
        ChatMessage(
            role=MessageRole.USER,
            content="The product 12345 is wireless "
            "noise-canceling headphones with 20-hour battery life, and user review is "
            "'The headphones broke after one week of use. Very poor quality'.",
        ),
    ]


def test_prompt_lack_one_argument(text_prompt: LocalPrompt) -> None:  # noqa: D103
    assert text_prompt.format_string(
        product_id="12345",
        review="The headphones broke after one week of use. Very poor quality",
    ) == (
        "You ara a product review analyzer, please generate a score and the "
        "dislike reasons(if any) for the review. The product 12345 is {description}, "
        "and user review is 'The headphones broke after one week of use. Very poor quality'."
    )


def test_prompt_contain_json_schema() -> None:  # noqa: D103
    prompt = Prompt.from_text(
        text=f"The json schema is {LocalPrompt.model_json_schema(mode='serialization')}",
    )
    prompt.format_string()
