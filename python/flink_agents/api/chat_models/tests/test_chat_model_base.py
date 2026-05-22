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
from typing import Any, Dict, List, Sequence

import pytest
from pydantic import Field, ValidationError

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.tools.tool import Tool


class _MinimalChatModelSetup(BaseChatModelSetup):
    """Minimal subclass that omits the `model` field declaration.

    Used to assert the `model` field is inherited from `BaseChatModelSetup`.
    """

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings derived from the inherited `model` field."""
        return {"model": self.model}


class _RecordingConnection(BaseChatModelConnection):
    """Connection that captures the messages it receives for inspection."""

    captured_messages: List[ChatMessage] = Field(default_factory=list)

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        self.captured_messages = list(messages)
        return ChatMessage(role=MessageRole.ASSISTANT, content="ok")


class _RecordingChatModelSetup(BaseChatModelSetup):
    """Subclass that lets tests inject a connection without calling open()."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}


def _build_setup(
    prompt: Prompt,
) -> tuple[_RecordingChatModelSetup, _RecordingConnection]:
    setup = _RecordingChatModelSetup(connection="c", model="m", prompt=prompt)
    connection = _RecordingConnection()
    setup._resolved_connection = connection
    return setup, connection


def test_inherits_model_field_from_base() -> None:
    """A subclass that omits `model` still exposes it via inheritance."""
    setup = _MinimalChatModelSetup(connection="c", model="m1")
    assert setup.model == "m1"


def test_missing_model_raises_validation_error() -> None:
    """Constructing without `model` must raise a Pydantic ValidationError."""
    with pytest.raises(ValidationError):
        _MinimalChatModelSetup(connection="c")


def test_chat_fills_template_from_arguments_parameter() -> None:
    """chat() fills the prompt template from the `arguments` parameter."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    setup.chat([], arguments={"key": "value"})

    assert len(connection.captured_messages) == 1
    assert connection.captured_messages[0].content == "Task: value"


def test_chat_does_not_read_template_vars_from_extra_args() -> None:
    """chat() must not read template variables from ChatMessage.extra_args."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    user_message = ChatMessage(
        role=MessageRole.USER, content="hello", extra_args={"key": "value"}
    )
    setup.chat([user_message], arguments={})

    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content == "Task: {key}"
    assert connection.captured_messages[1].content == "hello"


def test_chat_refills_template_on_subsequent_invocations() -> None:
    """Each chat() invocation must re-fill the prompt template from the args."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    setup.chat([], arguments={"key": "v1"})
    assert len(connection.captured_messages) == 1
    assert connection.captured_messages[0].content == "Task: v1"

    tool_response = ChatMessage(role=MessageRole.TOOL, content="tool result")
    setup.chat([tool_response], arguments={"key": "v1"})
    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content == "Task: v1"
    assert connection.captured_messages[1].content == "tool result"
