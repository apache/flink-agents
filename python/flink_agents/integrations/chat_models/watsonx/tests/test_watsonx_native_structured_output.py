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
import json
import os
from typing import Any, Callable, Dict, List, Mapping, Tuple
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.watsonx.watsonx_chat_model import (
    DEFAULT_MODEL,
    WatsonxChatModelConnection,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

test_model = os.environ.get("WATSONX_CHAT_MODEL", DEFAULT_MODEL)
credentials_available = (
    "WATSONX_URL" in os.environ
    and ("WATSONX_API_KEY" in os.environ or "WATSONX_TOKEN" in os.environ)
) and ("WATSONX_PROJECT_ID" in os.environ or "WATSONX_SPACE_ID" in os.environ)


class Report(BaseModel):
    """Output schema fixture, shaped like the Java suite's fixture of the same name.

    The shapes are held in step so the two languages can be compared: a plain field,
    a map with a typed value, an omissible field, and a second plain field.
    """

    summary: str
    counts: Dict[str, int]
    note: str | None = None
    total: int


class Unrenderable(BaseModel):
    """A schema carrying a member that no JSON Schema can express."""

    cb: Callable[[int], int]


class Answer(BaseModel):
    """Flat output schema for the live structured-output call."""

    verdict: str
    score: int


# What the Java suite pins for the Report shape. The two documents are compared on
# property names, the required set and value types rather than byte for byte:
# victools always emits $schema and never title, and pydantic is the reverse.
JAVA_PROPERTY_NAMES = {"summary", "counts", "note", "total"}
JAVA_REQUIRED = {"summary", "counts", "total"}

CALLER_FORMAT = {"type": "json_object"}

CHAT_RESPONSE = {
    "id": "chatcmpl-1",
    "model_id": test_model,
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "{}"},
            "finish_reason": "stop",
        }
    ],
}


def _connection() -> WatsonxChatModelConnection:
    """A connection built from fake credentials, contacting nothing on its own."""
    return WatsonxChatModelConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )


def _mocked_connection(
    monkeypatch: pytest.MonkeyPatch,
) -> Tuple[WatsonxChatModelConnection, MagicMock]:
    """A connection whose provider call is a mock, returned alongside it.

    Assigning the private client is what keeps the call offline: the connection
    builds a real one lazily on first use otherwise.
    """
    provider_model = MagicMock()
    provider_model.chat.return_value = CHAT_RESPONSE
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.ModelInference",
        MagicMock(return_value=provider_model),
    )
    connection = _connection()
    connection._client = MagicMock()
    return connection, provider_model


def _sent_params(
    monkeypatch: pytest.MonkeyPatch,
    output_schema: OutputSchema | None = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """The params dict one chat call hands to the provider."""
    connection, provider_model = _mocked_connection(monkeypatch)
    connection.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")],
        output_schema=output_schema,
        **kwargs,
    )
    return provider_model.chat.call_args.kwargs["params"] or {}


@pytest.mark.parametrize("effective_model", [DEFAULT_MODEL, "no-such-model", None])
def test_supports_native_structured_output_is_unconditional(
    effective_model: str | None,
) -> None:
    """Capability is reported for any model, including one nothing recognizes.

    The constraint is applied by the serving runtime rather than by the model, so a
    gate on the model string would turn callers away from a path that works.
    """
    assert _connection().supports_native_structured_output(effective_model) is True


def test_params_carry_response_format_for_basemodel_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A BaseModel schema reaches the provider as the endpoint's json_schema form.

    The schema slot is the model's plain rendering. A stricter rendering would force
    every field required and forbid additional properties, neither of which the Java
    side emits.
    """
    params = _sent_params(monkeypatch, output_schema=OutputSchema(output_schema=Report))

    assert params["response_format"] == {
        "type": "json_schema",
        "json_schema": {
            "name": "Report",
            "schema": Report.model_json_schema(),
            "strict": True,
        },
    }


def test_params_omit_response_format_without_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No schema leaves the key absent rather than present and empty."""
    params = _sent_params(monkeypatch, temperature=0.5)

    assert "response_format" not in params


def test_row_type_info_schema_returns_and_sends_no_response_format(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A RowTypeInfo schema is accepted and left to the prompt-engineering fallback.

    Both halves are asserted. Reporting native capability exempts this connection
    from the tree-wide rule that a connection rejects a schema it cannot translate,
    so nothing else pins that a schema form with no native translation still returns
    a response, and that no format is derived from it.
    """
    schema = OutputSchema(
        output_schema=RowTypeInfo([BasicTypeInfo.INT_TYPE_INFO()], ["total"])
    )
    connection, provider_model = _mocked_connection(monkeypatch)

    response = connection.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")],
        output_schema=schema,
        temperature=0.5,
    )

    assert response.role == MessageRole.ASSISTANT
    assert "response_format" not in provider_model.chat.call_args.kwargs["params"]


def test_unrenderable_model_reports_render_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A model with no JSON Schema is reported as that, naming the model.

    Rendering through the shared helper is what produces this wording. Calling the
    model's own rendering directly would surface an error naming neither the schema
    nor a way out of it.
    """
    with pytest.raises(
        TypeError, match="Unrenderable cannot be rendered as a JSON Schema"
    ):
        _sent_params(
            monkeypatch, output_schema=OutputSchema(output_schema=Unrenderable)
        )


@pytest.mark.parametrize(
    "caller_kwargs",
    [
        pytest.param({"response_format": CALLER_FORMAT}, id="kwarg"),
        pytest.param(
            {"additional_kwargs": {"response_format": CALLER_FORMAT}},
            id="additional_kwargs",
        ),
    ],
)
def test_schema_and_caller_response_format_raises(
    monkeypatch: pytest.MonkeyPatch, caller_kwargs: Dict[str, Any]
) -> None:
    """A schema and a caller response format with a value cannot both be honored.

    Both channels reach the same request field, so both are guarded. Choosing the
    derived value would drop a setting the caller made deliberately, and choosing the
    caller's would report the schema as applied when it was not.
    """
    with pytest.raises(
        ValueError, match="Report output schema is sent as response_format"
    ):
        _sent_params(
            monkeypatch,
            output_schema=OutputSchema(output_schema=Report),
            **caller_kwargs,
        )


@pytest.mark.parametrize(
    ("output_schema", "caller_kwargs"),
    [
        pytest.param(None, {"response_format": CALLER_FORMAT}, id="no schema, kwarg"),
        pytest.param(
            None,
            {"additional_kwargs": {"response_format": CALLER_FORMAT}},
            id="no schema, additional_kwargs",
        ),
        pytest.param(
            OutputSchema(
                output_schema=RowTypeInfo([BasicTypeInfo.INT_TYPE_INFO()], ["total"])
            ),
            {"response_format": CALLER_FORMAT},
            id="untranslatable schema, kwarg",
        ),
    ],
)
def test_caller_response_format_survives_when_no_schema_is_sent(
    monkeypatch: pytest.MonkeyPatch,
    output_schema: OutputSchema | None,
    caller_kwargs: Dict[str, Any],
) -> None:
    """A caller response format is forwarded whenever no schema is derived.

    response_format is on none of the reserved sets, so callers set it directly
    today and the rejection belongs to the branch that actually derives a schema.
    Applied any wider it turns those calls into errors. The branch is skipped for two
    distinct reasons, no schema at all and a schema with no native translation, and
    the caller's value has to survive both.
    """
    params = _sent_params(monkeypatch, output_schema=output_schema, **caller_kwargs)

    assert params["response_format"] == CALLER_FORMAT


def test_null_caller_response_format_is_not_a_conflict(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A caller response format of None is replaced by the derived one, not refused.

    Nothing the caller set survives to compete with the schema, so there is no
    conflict to report and the request still carries the derived envelope.
    """
    params = _sent_params(
        monkeypatch,
        output_schema=OutputSchema(output_schema=Report),
        response_format=None,
    )

    assert params["response_format"]["json_schema"]["name"] == "Report"


def test_derived_schema_matches_java_semantically(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The document sent from Python states the contract the Java one states.

    A caller may reach the same model through either language, so the two must agree
    on which properties exist, which of them the response must carry, and what a map
    value has to be.
    """
    params = _sent_params(monkeypatch, output_schema=OutputSchema(output_schema=Report))
    schema = params["response_format"]["json_schema"]["schema"]

    assert set(schema["properties"]) == JAVA_PROPERTY_NAMES
    assert set(schema["required"]) == JAVA_REQUIRED
    assert schema["properties"]["summary"]["type"] == "string"
    assert schema["properties"]["total"]["type"] == "integer"
    assert schema["properties"]["counts"]["additionalProperties"]["type"] == "integer"


def test_live_fixture_document_omits_additional_properties() -> None:
    """The live call's schema states the same contract the Java recipe states.

    A model config forbidding extras would render additionalProperties into the
    document, so the two languages would send different contracts for one fixture.
    Nothing else offline reads what the live call sends.
    """
    assert "additionalProperties" not in Answer.model_json_schema()


@pytest.mark.integration
@pytest.mark.skipif(
    not credentials_available, reason="watsonx.ai credentials are not set"
)
def test_chat_with_output_schema() -> None:
    """A schema comes back as content that parses into the model it described.

    The reply's own keys are checked, not just that it parses, because nothing else
    here would notice an extra one: no additionalProperties is sent, so the schema
    permits it, and the reader drops a property it does not declare.

    A reply that fails this is a result to investigate rather than a diagnosis on its
    own: the schema may not have been applied, or may not have described the type the
    caller passed.
    """
    response = WatsonxChatModelConnection().chat(
        [
            ChatMessage(
                role=MessageRole.USER,
                content='Rate the sentence "the build is green" and report a verdict'
                " and a score.",
            )
        ],
        output_schema=OutputSchema(output_schema=Answer),
        model=test_model,
        max_tokens=200,
    )

    assert response.content is not None
    assert response.content.strip() != ""
    parsed = json.loads(response.content)
    assert set(parsed) == {"verdict", "score"}
    assert Answer(**parsed).verdict is not None


def _judging_connection(
    monkeypatch: pytest.MonkeyPatch,
) -> Tuple[WatsonxChatModelConnection, list]:
    """A mocked connection recording every model its request path judges.

    Subclassing keeps the predicate itself under test rather than standing a stub in
    for it: the override notes what it was asked about and delegates to the real one.
    """
    judged: list = []

    class _JudgingConnection(WatsonxChatModelConnection):
        def supports_native_structured_output(
            self, effective_model: str | None
        ) -> bool:
            judged.append(effective_model)
            return super().supports_native_structured_output(effective_model)

    provider_model = MagicMock()
    provider_model.chat.return_value = CHAT_RESPONSE
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.ModelInference",
        MagicMock(return_value=provider_model),
    )
    connection = _JudgingConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )
    connection._client = MagicMock()
    return connection, judged


def test_effective_model_for_applies_the_default_model() -> None:
    """A call naming no model resolves to the model the request would be issued to.

    Reading the parameter alone would answer ``None`` where the request in fact goes
    to the default model, so the hook and the request would disagree on every call
    that names no model.
    """
    assert _connection().effective_model_for({}) == DEFAULT_MODEL
    assert _connection().effective_model_for(None) == DEFAULT_MODEL


def test_effective_model_for_reads_an_explicit_model() -> None:
    """A named model is answered as given, not replaced by the default."""
    assert _connection().effective_model_for({"model": "no-such-model"}) == (
        "no-such-model"
    )


def test_effective_model_for_keeps_a_present_but_empty_model() -> None:
    """The default stands in for an absent model only, matching the request builder.

    The builder's fallback is a ``pop`` default, which applies when the key is missing
    and not when it is present and empty.
    """
    assert _connection().effective_model_for({"model": ""}) == ""


def test_effective_model_for_does_not_consume_the_model() -> None:
    """The hook reads the key that ``chat`` pops, and has to leave it in place."""
    model_kwargs = {"model": "no-such-model"}

    _connection().effective_model_for(model_kwargs)

    assert model_kwargs == {"model": "no-such-model"}


@pytest.mark.parametrize(
    "model_kwargs",
    [{"model": "no-such-model"}, {"model": ""}, {}],
    ids=["named", "blank", "absent"],
)
def test_effective_model_for_names_the_model_the_request_judges(
    monkeypatch: pytest.MonkeyPatch, model_kwargs: Dict[str, Any]
) -> None:
    """The hook names exactly the model the request path asks the predicate about.

    Capability here is unconditional, so the predicate's answer cannot reveal a
    disagreement; only the argument it was handed can. A fresh connection per case is
    what makes the single-element comparison also an assertion that the predicate was
    reached at all.
    """
    connection, judged = _judging_connection(monkeypatch)

    named = connection.effective_model_for(model_kwargs)
    connection.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")],
        output_schema=OutputSchema(output_schema=Answer),
        **model_kwargs,
    )

    assert judged == [named]


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


def _query_recording_connection(
    monkeypatch: pytest.MonkeyPatch,
) -> Tuple[WatsonxChatModelConnection, MagicMock, List[bool]]:
    """A connection recording what the feasibility query answered on each request.

    Subclassing keeps the query itself under test rather than standing a stub in for
    it: the override notes the answer it gave and delegates to the real one.
    """
    answers: List[bool] = []

    class _RecordingConnection(WatsonxChatModelConnection):
        def can_apply_native_structured_output(
            self,
            output_schema: OutputSchema | None,
            tools: List[Tool] | None,
            model_kwargs: Mapping[str, Any] | None,
        ) -> bool:
            answer = super().can_apply_native_structured_output(
                output_schema, tools, model_kwargs
            )
            answers.append(answer)
            return answer

    provider_model = MagicMock()
    provider_model.chat.return_value = CHAT_RESPONSE
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.ModelInference",
        MagicMock(return_value=provider_model),
    )
    connection = _RecordingConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )
    connection._client = MagicMock()
    return connection, provider_model, answers


def test_feasibility_query_agrees_with_the_native_branch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The answer matches whether the payload ends up carrying a response_format.

    Comparing the answer against what the payload carries, rather than against a
    literal, is what keeps the query and the branch from drifting in step. This
    connection reports every model capable, so the schema form is the only thing that
    moves. Clearing the record per case makes the single-element comparison an
    assertion that the query was reached exactly once on that request, too.
    """
    conn, provider_model, answers = _query_recording_connection(monkeypatch)
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    row_type = RowTypeInfo(
        field_types=[BasicTypeInfo.STRING_TYPE_INFO()], field_names=["name"]
    )

    for schema in (
        OutputSchema(output_schema=Report),
        OutputSchema(output_schema=row_type),
        None,
    ):
        for tools in (None, [], [tool]):
            answers.clear()

            conn.chat(
                [ChatMessage(role=MessageRole.USER, content="Hello!")],
                tools=tools,
                output_schema=schema,
            )

            params = provider_model.chat.call_args.kwargs["params"] or {}
            carried = "response_format" in params
            assert answers == [carried], f"schema {schema}, tools {tools}"


def test_feasibility_query_excludes_model_capability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Feasibility is answered without consulting the capability predicate.

    This connection reports every model capable, so no model name can separate the two
    answers. A subclass that reports nothing capable can: the query must still answer
    ``True``, which fails the moment a capability conjunct is folded into the override.
    That folding is invisible to the binding test above, which moves both sides at once.
    The payload stays unconstrained meanwhile, which is the branch's own conjunct doing
    the work the query does not.
    """

    class _IncapableConnection(WatsonxChatModelConnection):
        def supports_native_structured_output(
            self, effective_model: str | None
        ) -> bool:
            return False

    provider_model = MagicMock()
    provider_model.chat.return_value = CHAT_RESPONSE
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.ModelInference",
        MagicMock(return_value=provider_model),
    )
    conn = _IncapableConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )
    conn._client = MagicMock()

    assert (
        conn.can_apply_native_structured_output(
            OutputSchema(output_schema=Report), [], {"model": DEFAULT_MODEL}
        )
        is True
    )

    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")],
        output_schema=OutputSchema(output_schema=Report),
    )
    assert "response_format" not in (
        provider_model.chat.call_args.kwargs["params"] or {}
    )


def test_feasibility_query_ignores_a_caller_response_format() -> None:
    """A caller-supplied response_format does not make a translatable schema infeasible.

    The branch answers that conflict by raising rather than by skipping, so the query
    has to keep answering ``True`` here. Reporting it infeasible instead would turn a
    documented error into a silently unconstrained request.
    """
    conn = _connection()
    schema = OutputSchema(output_schema=Report)

    assert (
        conn.can_apply_native_structured_output(
            schema, [], {"model": DEFAULT_MODEL, "response_format": CALLER_FORMAT}
        )
        is True
    )
    assert (
        conn.can_apply_native_structured_output(
            schema,
            [],
            {
                "model": DEFAULT_MODEL,
                "additional_kwargs": {"response_format": CALLER_FORMAT},
            },
        )
        is True
    )


def test_feasibility_query_is_asked_with_the_unstripped_kwargs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The query sees the parameters as they arrived, not a copy ``chat`` has stripped.

    ``chat`` removes ``model``, ``extract_reasoning``, ``tool_choice``,
    ``tool_choice_option`` and ``additional_kwargs`` from its own mapping before the
    native branch runs. Asked with that copy, an override reading any of them would
    answer about a request other than the one being built. No term of today's answer
    reads them, so this pins the shape rather than a live defect.
    """
    asked: List[Mapping[str, Any] | None] = []

    class _CapturingConnection(WatsonxChatModelConnection):
        def can_apply_native_structured_output(
            self,
            output_schema: OutputSchema | None,
            tools: List[Tool] | None,
            model_kwargs: Mapping[str, Any] | None,
        ) -> bool:
            asked.append(model_kwargs)
            return super().can_apply_native_structured_output(
                output_schema, tools, model_kwargs
            )

    provider_model = MagicMock()
    provider_model.chat.return_value = CHAT_RESPONSE
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.ModelInference",
        MagicMock(return_value=provider_model),
    )
    conn = _CapturingConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )
    conn._client = MagicMock()

    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")],
        model=DEFAULT_MODEL,
        extract_reasoning=True,
        additional_kwargs={"user": "someone"},
        output_schema=OutputSchema(output_schema=Report),
    )

    assert len(asked) == 1
    assert asked[0] is not None
    assert asked[0]["model"] == DEFAULT_MODEL
    assert asked[0]["extract_reasoning"] is True
    assert asked[0]["additional_kwargs"] == {"user": "someone"}
