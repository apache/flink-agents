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
from typing import Any, Callable, List, Mapping
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import NativeStructuredOutputSupport
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.dashscope_chat_model import (
    DashScopeChatModelConnection,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

# The models DashScope documents native structured output for on the
# text-generation endpoint this connection calls. The names are written out here
# rather than read from the connection, so that a name mistyped there is a
# disagreement between two lists rather than a value both sides share.
_CAPABLE_MODEL = "qwen3.7-max"
_CAPABLE_MODELS = [
    "qwen3.7-max",
    "qwen3.7-max-preview",
    "qwen3.7-max-2026-05-17",
    "qwen3.7-max-2026-05-20",
]

# Names that must not be treated as capable. qwen-plus is the connection's default
# model, qwen3.7-max-2026-06-08 is the member of the capable family served only on
# the multimodal interface, and qwen3.8-max is a schema-capable family reachable
# only through that interface.
_INCAPABLE_MODELS = [
    "qwen-plus",
    "qwen-turbo",
    "qwen3.8-max",
    "qwen3.7-max-2026-06-08",
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


def _connection() -> DashScopeChatModelConnection:
    return DashScopeChatModelConnection(api_key="fake-key")


def _messages() -> list[ChatMessage]:
    return [ChatMessage.of(role=MessageRole.USER, content="hi")]


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
        "flink_agents.integrations.chat_models.dashscope_chat_model.Generation.call",
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


def test_unlisted_model_answers_feasible_and_still_carries_the_schema(
    monkeypatch,
) -> None:
    """An unlisted model is merely feasible, yet its request carries the full schema.

    The default model is unlisted, and omitting ``model`` is the path every existing
    caller is on. Folding capability into feasibility would report INFEASIBLE, which a
    NATIVE policy cannot overrule; folding it into the request would drop a schema that
    policy chose to send. The request carries what a listed model's request carries,
    not a weaker form.
    """
    schema = OutputSchema(output_schema=Person)

    assert (
        _connection().supports_native_structured_output(schema, [], {})
        is NativeStructuredOutputSupport.FEASIBLE
    )

    _, listed = _chat(monkeypatch, model=_CAPABLE_MODEL, output_schema=schema)
    response, kwargs = _chat(monkeypatch, output_schema=schema)
    assert response.text == "ok"
    assert kwargs["response_format"] == listed["response_format"]


def test_non_string_model_answers_feasible_and_still_carries_the_schema(
    monkeypatch,
) -> None:
    """A model that is not a string is answered ``FEASIBLE`` rather than with an error.

    This connection's capability predicate tests membership of a frozenset allowlist,
    which answers for any hashable value without raising. Sibling connections classify
    by other means and several raise on this input, so the tolerance is local to this
    connection rather than a contract every connection keeps. The request still
    carries the schema, and the value reaches the provider as the model unchanged.
    """
    schema = OutputSchema(output_schema=Person)
    assert (
        _connection().supports_native_structured_output(schema, [], {"model": 123})
        is NativeStructuredOutputSupport.FEASIBLE
    )

    response, kwargs = _chat(monkeypatch, model=123, output_schema=schema)
    assert response.text == "ok"
    assert kwargs["model"] == 123
    assert kwargs["response_format"]["type"] == "json_schema"


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
    assert response.text == "ok"
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
def test_query_recommends_native_for_capable_models(model: str) -> None:
    """Every model documented as schema-capable on the text interface is recommended."""
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], {"model": model}
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )


@pytest.mark.parametrize("model", _INCAPABLE_MODELS)
def test_query_reports_incapable_models_feasible(model: str | None) -> None:
    """Other families, a multimodal-only snapshot, and no model stay merely feasible.

    The schema is translatable, so the request is not infeasible: capability is
    advisory and kept out of the binding half of the answer.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], {"model": model}
        )
        is NativeStructuredOutputSupport.FEASIBLE
    )


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
    assert response.text == "ok"
    assert kwargs["response_format"] == caller_format


# The connection's default model, written out here rather than imported for the same
# reason as the lists above: a changed default is then a disagreement between two
# values rather than one value both sides read.
_DEFAULT_MODEL = "qwen-plus"


def _judging_connection() -> tuple[DashScopeChatModelConnection, list[str | None]]:
    """A connection recording every model it judges for capability.

    Subclassing keeps the predicate itself under test rather than standing a stub in
    for it: the override notes what it was asked about and delegates to the real one.
    """
    judged: list[str | None] = []

    class _JudgingConnection(DashScopeChatModelConnection):
        def _model_supports_native_structured_output(
            self, effective_model: str | None
        ) -> bool:
            judged.append(effective_model)
            return super()._model_supports_native_structured_output(effective_model)

    return _JudgingConnection(api_key="fake-key"), judged


def test_query_judges_the_default_model_for_an_absent_model() -> None:
    """A call naming no model is judged as the model the request would be issued to.

    Reading the parameter alone would judge ``None`` for every such call and report the
    default model incapable without ever asking about it. The default stands in for an
    absent model only, matching the request builder's ``pop`` default, so a present but
    empty model is judged as given.
    """
    conn, judged = _judging_connection()
    schema = OutputSchema(output_schema=Person)

    for model_kwargs in ({}, None, {"model": ""}):
        conn.supports_native_structured_output(schema, [], model_kwargs)

    assert judged == [_DEFAULT_MODEL, _DEFAULT_MODEL, ""]


def test_query_does_not_consume_the_model() -> None:
    """The query reads the key that ``chat`` pops, and has to leave it in place."""
    model_kwargs = {"model": _CAPABLE_MODEL}

    _connection().supports_native_structured_output(
        OutputSchema(output_schema=Person), [], model_kwargs
    )

    assert model_kwargs == {"model": _CAPABLE_MODEL}


@pytest.mark.parametrize(
    "model_kwargs",
    [{"model": _CAPABLE_MODEL}, {"model": "qwen-turbo"}, {"model": ""}, {}],
    ids=["capable", "incapable", "blank", "absent"],
)
def test_query_judges_the_model_the_request_is_issued_against(
    monkeypatch, model_kwargs: dict[str, Any]
) -> None:
    """The query asks about exactly the model the request is issued against.

    The query resolves the effective model separately from the builder, so only
    comparing the model it judges with the model the request names keeps the two from
    drifting apart.
    """
    conn, judged = _judging_connection()
    mock_call = _patched_call(monkeypatch)
    schema = OutputSchema(output_schema=Person)

    conn.supports_native_structured_output(schema, [], model_kwargs)
    conn.chat(_messages(), output_schema=schema, **model_kwargs)

    assert judged[0] == mock_call.call_args.kwargs["model"]


def _add(a: int, b: int) -> int:
    """Add two integers.

    Parameters
    ----------
    a : int
        first
    b : int
        second

    Returns:
    -------
    int
        sum
    """
    return a + b


def test_query_agrees_with_the_native_branch(monkeypatch) -> None:
    """The answer is INFEASIBLE exactly when the request carries no response_format.

    Comparing the answer against what the request carries, rather than only against a
    literal, is what keeps the query and the branch from drifting in step. The schema
    form, the tools and the model's capability all move; the exact answer is pinned as
    well, so capability can only separate FEASIBLE from NATIVE_RECOMMENDED.
    """
    conn = _connection()
    mock_call = _patched_call(monkeypatch)
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])

    for schema in (
        OutputSchema(output_schema=Person),
        OutputSchema(output_schema=row_type),
        None,
    ):
        for tools in (None, [], [tool]):
            for model in (_CAPABLE_MODEL, "qwen-turbo"):
                support = conn.supports_native_structured_output(
                    schema, tools, {"model": model}
                )

                conn.chat(_messages(), tools=tools, output_schema=schema, model=model)

                label = f"schema {schema}, tools {tools}, model {model}"
                if schema is None or schema.output_schema is not Person:
                    expected = NativeStructuredOutputSupport.INFEASIBLE
                elif model == _CAPABLE_MODEL:
                    expected = NativeStructuredOutputSupport.NATIVE_RECOMMENDED
                else:
                    expected = NativeStructuredOutputSupport.FEASIBLE
                carried = "response_format" in mock_call.call_args.kwargs
                assert support is expected, label
                assert carried == (
                    support is not NativeStructuredOutputSupport.INFEASIBLE
                ), label


def test_query_ignores_a_caller_response_format() -> None:
    """A caller-supplied response_format does not make a translatable schema infeasible.

    The branch answers that conflict by raising rather than by skipping, so the query
    has to keep recommending native here. Reporting it infeasible instead would turn
    a documented error into a silently unconstrained request.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person),
            [],
            {"model": _CAPABLE_MODEL, "response_format": {"type": "json_object"}},
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )


def test_feasibility_is_asked_with_the_unstripped_kwargs(monkeypatch) -> None:
    """Feasibility sees the parameters as they arrived, not a copy ``chat`` stripped.

    ``chat`` removes ``model``, ``api_key`` and ``extract_reasoning`` from its own
    mapping before the native branch runs. Asked with that copy, an override reading
    any of them would answer about a request other than the one being built. No term of
    today's answer reads them, so this pins the shape rather than a live defect.
    """
    asked: List[Mapping[str, Any] | None] = []

    class _CapturingConnection(DashScopeChatModelConnection):
        def _can_apply_native_structured_output(
            self,
            output_schema: OutputSchema | None,
            tools: List[Tool] | None,
            model_kwargs: Mapping[str, Any] | None,
        ) -> bool:
            asked.append(model_kwargs)
            return super()._can_apply_native_structured_output(
                output_schema, tools, model_kwargs
            )

    _patched_call(monkeypatch)
    _CapturingConnection(api_key="fake-key").chat(
        _messages(),
        model=_CAPABLE_MODEL,
        extract_reasoning=True,
        output_schema=OutputSchema(output_schema=Person),
    )

    assert len(asked) == 1
    assert asked[0] is not None
    assert asked[0]["model"] == _CAPABLE_MODEL
    assert asked[0]["extract_reasoning"] is True
