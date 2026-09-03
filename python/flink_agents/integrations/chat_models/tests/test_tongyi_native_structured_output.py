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
from types import SimpleNamespace
from typing import Any, Callable
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.tongyi_chat_model import (
    TongyiChatModelConnection,
)

# The model family DashScope documents native structured output for on the
# text-generation endpoint this connection calls. The names are written out here
# rather than read from the connection, so that a name mistyped there is a
# disagreement between two lists rather than a value both sides share.
_CAPABLE_MODEL = "qwen3.7-max"
_CAPABLE_MODELS = ["qwen3.7-max", "qwen3.7-max-2026-05-20"]

# Names that must not be treated as capable. qwen-plus is the connection's default
# model, qwen3.7-maximum is the near miss a bare prefix test would admit, and
# qwen3.8-max is a family reachable only through a different endpoint.
_INCAPABLE_MODELS = [
    "qwen-plus",
    "qwen-turbo",
    "qwen3.8-max",
    "qwen3.7-maximum",
    "",
    None,
]


class Person(BaseModel):
    """A representative flat BaseModel output schema."""

    name: str
    age: int


class Unrenderable(BaseModel):
    """A schema carrying a member that no JSON Schema can express."""

    cb: Callable[[int], int]


def _connection() -> TongyiChatModelConnection:
    return TongyiChatModelConnection(api_key="fake-key")


def _messages() -> list[ChatMessage]:
    return [ChatMessage(role=MessageRole.USER, content="hi")]


def _mocked_response() -> SimpleNamespace:
    """The minimum response shape the connection reads back after the call."""
    return SimpleNamespace(
        status_code=200,
        output={
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "ok",
                        "tool_calls": None,
                    }
                }
            ]
        },
        usage=SimpleNamespace(input_tokens=1, output_tokens=2),
    )


def _patched_call(monkeypatch: pytest.MonkeyPatch) -> MagicMock:
    """Stand a mock in for the provider call and hand it back to the caller.

    Returned rather than kept private so a test can assert on whether the provider
    was reached at all, not only on what it received.
    """
    mock_call = MagicMock(return_value=_mocked_response())
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.tongyi_chat_model.Generation.call",
        mock_call,
    )
    return mock_call


def _chat(
    monkeypatch: pytest.MonkeyPatch, **chat_kwargs: Any
) -> tuple[ChatMessage, dict[str, Any]]:
    """Drive one chat call against a mocked provider, so no server is contacted.

    Returns the response together with the keyword arguments the provider call
    received, which is the whole request: every argument reaches the provider
    entry point as a keyword.
    """
    mock_call = _patched_call(monkeypatch)
    response = _connection().chat(_messages(), **chat_kwargs)
    return response, mock_call.call_args.kwargs


def test_native_response_format_applied_on_capable_model(monkeypatch) -> None:
    """A BaseModel schema reaches a capable model as a json_schema response format.

    The document is the renderer's output as produced, so the equality assertion
    also pins that nothing post-processes it. ``result_format`` is asserted
    alongside because it sits one prefix away from ``response_format`` and the
    response-parsing path depends on it staying ``message``.
    """
    _, kwargs = _chat(
        monkeypatch,
        model=_CAPABLE_MODEL,
        output_schema=OutputSchema(output_schema=Person),
    )
    response_format = kwargs["response_format"]
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["name"] == "Person"
    assert response_format["json_schema"]["strict"] is True
    assert response_format["json_schema"]["schema"] == Person.model_json_schema()
    assert kwargs["result_format"] == "message"


def test_native_not_applied_for_default_model(monkeypatch) -> None:
    """The default model answers a schema instead of refusing it, and sends none.

    Omitting ``model`` is the path every existing caller is on, so the schema is
    answered with the prompt-engineering fallback rather than raising, and no
    undocumented parameter reaches the request.
    """
    response, kwargs = _chat(
        monkeypatch, output_schema=OutputSchema(output_schema=Person)
    )
    assert response.content == "ok"
    assert "response_format" not in kwargs


def test_native_not_applied_when_schema_none(monkeypatch) -> None:
    """A call without a schema carries no response format key at all.

    The key must be absent rather than present with a ``None`` value, which the
    provider would read as a parameter it was given.
    """
    _, kwargs = _chat(monkeypatch, model=_CAPABLE_MODEL, output_schema=None)
    assert "response_format" not in kwargs


def test_native_not_applied_for_row_type_info(monkeypatch) -> None:
    """A RowTypeInfo schema falls back to prompting rather than raising.

    There is no native translation for it, so the request is left unchanged and
    no RowTypeInfo reaches the request body.
    """
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    response, kwargs = _chat(
        monkeypatch,
        model=_CAPABLE_MODEL,
        output_schema=OutputSchema(output_schema=row_type),
    )
    assert response.content == "ok"
    assert "response_format" not in kwargs


def test_unrenderable_schema_raises_naming_the_model(monkeypatch) -> None:
    """A schema that cannot be rendered fails here, named, not at the provider."""
    with pytest.raises(TypeError, match="Unrenderable cannot be rendered"):
        _chat(
            monkeypatch,
            model=_CAPABLE_MODEL,
            output_schema=OutputSchema(output_schema=Unrenderable),
        )


@pytest.mark.parametrize("model", _CAPABLE_MODELS)
def test_capability_predicate_accepts_capable_models(model: str) -> None:
    """The family alias and a dated snapshot below it are both capable."""
    assert _connection().supports_native_structured_output(model) is True


@pytest.mark.parametrize("model", _INCAPABLE_MODELS)
def test_capability_predicate_rejects_incapable_models(model: str | None) -> None:
    """Other families, a near-miss name, and an absent model name are not capable."""
    assert _connection().supports_native_structured_output(model) is False


def test_caller_supplied_response_format_conflicts(monkeypatch) -> None:
    """A caller's own response format and a schema collide, and the call is refused.

    Silently resolving the collision would either drop the caller's parameter or
    drop the schema, and neither is visible from the response. The refusal is
    asserted to reach the provider never, because raising after the call would
    still bill the caller for a response nothing reads.
    """
    mock_call = _patched_call(monkeypatch)
    with pytest.raises(ValueError, match="response_format must not also be passed"):
        _connection().chat(
            _messages(),
            model=_CAPABLE_MODEL,
            output_schema=OutputSchema(output_schema=Person),
            response_format={"type": "json_object"},
        )
    mock_call.assert_not_called()


def test_unrenderable_schema_conflicts_before_it_is_rendered(monkeypatch) -> None:
    """A caller's response format collides even with a schema that cannot render.

    The conflict is settled from the schema class, which is known without rendering,
    so it is reported ahead of a render failure the caller had already steered the
    request away from. Rendering first would report the wrong problem.
    """
    mock_call = _patched_call(monkeypatch)
    with pytest.raises(ValueError, match="response_format must not also be passed"):
        _connection().chat(
            _messages(),
            model=_CAPABLE_MODEL,
            output_schema=OutputSchema(output_schema=Unrenderable),
            response_format={"type": "json_object"},
        )
    mock_call.assert_not_called()


def test_row_type_info_leaves_a_caller_response_format_alone(monkeypatch) -> None:
    """A payload with no native translation is no conflict, so the caller wins.

    Nothing is derived from a RowTypeInfo, so there is no second response format to
    collide with the caller's and no reason to refuse the call. Testing the conflict
    before resolving the payload would raise here instead.
    """
    caller_format = {"type": "json_object"}
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    response, kwargs = _chat(
        monkeypatch,
        model=_CAPABLE_MODEL,
        output_schema=OutputSchema(output_schema=row_type),
        response_format=caller_format,
    )
    assert response.content == "ok"
    assert kwargs["response_format"] == caller_format
