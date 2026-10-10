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
from typing import Any, Callable, List, Mapping
from unittest.mock import MagicMock

import pytest
from openai.lib._pydantic import to_strict_json_schema
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import NativeStructuredOutputSupport
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.azure.azure_openai_chat_model import (
    AzureOpenAIChatModelConnection,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

# A deployment name is chosen by the user and carries no capability information, so
# every chat() call here uses one that is not a model name.
DEPLOYMENT = "my-deployment"

CAPABLE_API_VERSION = "2024-08-01-preview"

BELOW_FLOOR_API_VERSION = "2024-02-01"

CALLER_RESPONSE_FORMAT = {"type": "json_object"}


class Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str
    age: int


class Unrenderable(BaseModel):
    """A schema carrying a member that no JSON Schema can express."""

    cb: Callable[[int], int]


class FieldLess(BaseModel):
    """A schema declaring no fields, so it constrains nothing."""


class NestsFieldLess(BaseModel):
    """A field-less schema one level down, reached through a ``$ref``."""

    inner: FieldLess


class MapsToFieldLess(BaseModel):
    """A field-less schema reached through a map's ``additionalProperties``."""

    m: dict[str, FieldLess]


class Labelled(BaseModel):
    """A schema whose only member is a free-form map, a legitimate constraint."""

    labels: dict[str, str]


ROW_TYPE = Types.ROW_NAMED(["name"], [Types.STRING()])


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


def _connection(
    api_version: str = CAPABLE_API_VERSION,
) -> AzureOpenAIChatModelConnection:
    conn = AzureOpenAIChatModelConnection(
        api_key="test-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version=api_version,
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_message.refusal = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message, finish_reason="stop")
    ]
    mock_client.chat.completions.create.return_value.usage = None
    mock_client.chat.completions.create.return_value.id = "response-id"
    conn._client = mock_client
    return conn


def _create_call_kwargs(conn: AzureOpenAIChatModelConnection) -> dict[str, Any]:
    return conn.client.chat.completions.create.call_args.kwargs


def _chat_with_caller_response_format(
    conn: AzureOpenAIChatModelConnection,
    *,
    model_of_azure_deployment: str,
    in_additional_kwargs: bool,
    schema: Any = Person,
) -> None:
    """Chat with a caller-supplied response_format, optionally with an output schema.

    The value travels either inside additional_kwargs or as a direct kwarg; both end
    up in the same create() call. A ``schema`` of ``None`` sends no output schema at
    all rather than an empty one.
    """
    channel = (
        {"additional_kwargs": {"response_format": CALLER_RESPONSE_FORMAT}}
        if in_additional_kwargs
        else {"response_format": CALLER_RESPONSE_FORMAT}
    )
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment=model_of_azure_deployment,
        output_schema=None if schema is None else OutputSchema(output_schema=schema),
        **channel,
    )


def test_native_applied_for_capable_deployment_model() -> None:
    """response_format json_schema strict applied for a BaseModel on a capable model."""
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["name"] == "Person"
    assert response_format["json_schema"]["strict"] is True
    assert response_format["json_schema"]["schema"]["additionalProperties"] is False


def test_capable_native_request_still_targets_the_deployment() -> None:
    """The native branch leaves `model` as the deployment name.

    Capability is keyed on the backing model, but the provider is still addressed by
    deployment; substituting one for the other would route the call to a deployment
    that may not exist on the resource.
    """
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert _create_call_kwargs(conn)["model"] == DEPLOYMENT


def test_native_not_applied_when_deployment_model_absent() -> None:
    """Native NOT applied when the backing model of the deployment is unknown."""
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_unknown_deployment_model() -> None:
    """Native NOT applied for a backing model outside the allowlist."""
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="some-unknown-model",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_bare_gpt_4o() -> None:
    """Native NOT applied for a bare `gpt-4o` backing model.

    Azure carries model name and model version as separate properties, so a bare
    `gpt-4o` may be the 2024-05-13 version, which predates structured output support.
    """
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


@pytest.mark.parametrize("api_version", ["2024-08-01", "2024-10-21"])
def test_native_applied_for_ga_date_at_or_above_floor(api_version: str) -> None:
    """Native applied for a bare GA date at or above the floor.

    The documented floor is the preview form `2024-08-01-preview`, so these pin that a
    bare GA date carrying no `-preview` suffix is admitted, and that `2024-08-01` is the
    inclusive boundary.
    """
    conn = _connection(api_version=api_version)
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" in _create_call_kwargs(conn)


@pytest.mark.parametrize("api_version", ["v1", "latest"])
def test_native_not_applied_for_non_date_api_version(api_version: str) -> None:
    """Native NOT applied for an api-version outside the documented dated form.

    Every one of these sorts above the floor as a string, so only classifying the
    dated form keeps them out. The `v1` literal in particular does not reach Azure's
    v1 endpoint from here: `AzureOpenAI` sends it as a query parameter on the
    deployment-scoped chat/completions path.
    """
    conn = _connection(api_version=api_version)
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_api_version_below_floor() -> None:
    """Native NOT applied when the configured api-version predates the floor."""
    conn = _connection(api_version=BELOW_FLOOR_API_VERSION)
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_api_version_empty() -> None:
    """Native NOT applied when no api-version is configured.

    The empty string stands in for an absent api-version: the field is required at
    construction, so `None` is rejected by validation before chat() is ever reached.
    """
    conn = _connection(api_version="")
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_schema_none() -> None:
    """Native NOT applied when no output schema is supplied."""
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=None,
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_row_type_info() -> None:
    """Native NOT applied for a RowTypeInfo schema (BaseModel-only scope)."""
    conn = _connection()
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=ROW_TYPE),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_applied_even_when_tools_bound() -> None:
    """Native applied for a BaseModel even when tools are bound.

    Azure documents structured outputs as unsupported with parallel function calls,
    which constrains strict tool schemas rather than the response_format this branch
    sets, so binding tools does not gate it.
    """
    conn = _connection()
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    conn.chat(
        [ChatMessage.of(MessageRole.USER, "hi")],
        tools=[tool],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" in _create_call_kwargs(conn)


@pytest.mark.parametrize("in_additional_kwargs", [True, False])
def test_caller_response_format_conflicts_with_native_schema(
    in_additional_kwargs: bool,
) -> None:
    """A caller-supplied response_format alongside a natively applied schema raises.

    Both values would otherwise reach the same create() call, where the direct kwarg
    is silently overwritten and the additional_kwargs one becomes a duplicate keyword
    argument reported by the SDK rather than by this connection.
    """
    conn = _connection()
    with pytest.raises(ValueError, match="response_format") as excinfo:
        _chat_with_caller_response_format(
            conn,
            model_of_azure_deployment="gpt-4o-mini",
            in_additional_kwargs=in_additional_kwargs,
        )
    assert "Person" in str(excinfo.value)


def test_caller_response_format_conflict_precedes_the_schema_render() -> None:
    """A schema that cannot be rendered still reports the conflict, not the render.

    The conflict stands whatever the schema would have rendered to, and it names the
    two inputs the caller has to choose between. Rendering first would report a
    different problem, on a value this branch was never going to send.
    """
    with pytest.raises(ValueError, match="Unrenderable") as excinfo:
        _chat_with_caller_response_format(
            _connection(),
            model_of_azure_deployment="gpt-4o-mini",
            in_additional_kwargs=False,
            schema=Unrenderable,
        )
    assert "response_format must not also be passed" in str(excinfo.value)


@pytest.mark.parametrize("in_additional_kwargs", [True, False])
@pytest.mark.parametrize(
    ("api_version", "model_of_azure_deployment", "schema"),
    [
        (CAPABLE_API_VERSION, "gpt-4o", Person),
        (CAPABLE_API_VERSION, "gpt-4o-mini", ROW_TYPE),
        (CAPABLE_API_VERSION, "gpt-4o-mini", None),
        (BELOW_FLOOR_API_VERSION, "gpt-4o-mini", Person),
    ],
    ids=[
        "incapable_model",
        "row_type_info_schema",
        "no_output_schema",
        "api_version_below_floor",
    ],
)
def test_caller_response_format_survives_when_native_is_skipped(
    api_version: str,
    model_of_azure_deployment: str,
    schema: Any,
    in_additional_kwargs: bool,
) -> None:
    """The same caller input passes through untouched wherever native output is skipped.

    Native output is skipped for an incapable backing model, for a schema kind outside
    the natively translatable set, for no schema at all, and for an api-version below
    the floor. Only the branch that actually sends a schema as response_format may
    reject the caller's own value, so identical caller code has to keep working along
    every one of those paths, including the no-schema path taken by any caller that
    drives response_format itself.
    """
    conn = _connection(api_version=api_version)
    _chat_with_caller_response_format(
        conn,
        model_of_azure_deployment=model_of_azure_deployment,
        in_additional_kwargs=in_additional_kwargs,
        schema=schema,
    )
    assert _create_call_kwargs(conn)["response_format"] is CALLER_RESPONSE_FORMAT


@pytest.mark.parametrize(
    "model",
    [
        "gpt-5.1",
        "gpt-5.1-chat",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "o3-mini",
        "o1",
        "gpt-4o-mini",
        "gpt-4.1",
        "gpt-4.1-nano",
        "gpt-4.1-mini",
        "o4-mini",
        "o3",
    ],
)
def test_query_recommends_native_for_capable_models(model: str) -> None:
    """Every documented capable Azure model name behind a deployment is recommended.

    The list is the whole allowlist, so dropping an entry is caught rather than only
    narrowing capability silently.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], _model_kwargs(model)
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )


@pytest.mark.parametrize(
    "model",
    [
        "gpt-4o",
        "gpt-35-turbo",
        "gpt-4",
        "gpt-4o-2024-08-06",
        "some-unknown-model",
        "gpt-5.1-codex",
        "gpt-5.1-codex-mini",
        "gpt-5-pro",
        "gpt-5-codex",
        "codex-mini",
        "o3-pro",
        None,
        "",
    ],
)
def test_query_reports_incapable_models_feasible(model: str | None) -> None:
    """Incapable, Responses-only, unset and empty backing models stay merely feasible.

    A version-suffixed value such as `gpt-4o-2024-08-06` is an OpenAI snapshot name,
    not a name Azure reports as the model behind a deployment. The codex, `gpt-5-pro`
    and `o3-pro` names do support structured outputs but are served only on the
    Responses API, so they are incapable on the chat completions API this connection
    calls. The schema is translatable and the api-version reaches the floor, so the
    request is not infeasible: capability is advisory and kept out of that half.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], _model_kwargs(model)
        )
        is NativeStructuredOutputSupport.FEASIBLE
    )


def _chat_with_schema(conn: AzureOpenAIChatModelConnection, schema: Any) -> None:
    conn.chat(
        [ChatMessage.of(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=schema),
    )


def test_unrenderable_schema_raises_naming_the_model() -> None:
    """A schema that cannot be rendered fails here rather than at the provider."""
    with pytest.raises(TypeError, match="Unrenderable cannot be rendered"):
        _chat_with_schema(_connection(), Unrenderable)


@pytest.mark.parametrize("schema", [FieldLess, NestsFieldLess, MapsToFieldLess])
def test_field_less_schema_is_accepted_and_sent_whole(schema: type[BaseModel]) -> None:
    """A schema declaring no fields renders, so the provider decides on it, not us.

    The document reaches the request exactly as rendered rather than being refused
    here. The nested cases carry the field-less model below the root, so the
    assertion covers the whole document rather than only its top level.
    """
    conn = _connection()
    _chat_with_schema(conn, schema)
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["json_schema"]["schema"] == to_strict_json_schema(schema)


def test_map_member_schema_is_accepted_and_sent_whole() -> None:
    """A free-form map is a legitimate constraint and reaches the request intact."""
    conn = _connection()
    _chat_with_schema(conn, Labelled)
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["json_schema"]["schema"] == to_strict_json_schema(Labelled)


def _model_kwargs(backing_model: str | None = None) -> dict[str, Any]:
    """The parameter map a setup hands the connection for one request.

    ``model`` is the deployment, matching every other call in this module; the backing
    model travels under its own key and is omitted entirely when unset.
    """
    params: dict[str, Any] = {"model": DEPLOYMENT}
    if backing_model is not None:
        params["model_of_azure_deployment"] = backing_model
    return params


def _judging_connection() -> tuple[AzureOpenAIChatModelConnection, list[str | None]]:
    """A connection recording every model its request path judges for capability.

    Subclassing keeps the predicate itself under test rather than standing a stub in
    for it: the override notes what it was asked about and delegates to the real one.
    """
    judged: list[str | None] = []

    class _JudgingConnection(AzureOpenAIChatModelConnection):
        def _model_supports_native_structured_output(
            self, effective_model: str | None
        ) -> bool:
            judged.append(effective_model)
            return super()._model_supports_native_structured_output(effective_model)

    conn = _JudgingConnection(
        api_key="test-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version=CAPABLE_API_VERSION,
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_message.refusal = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message, finish_reason="stop")
    ]
    mock_client.chat.completions.create.return_value.usage = None
    mock_client.chat.completions.create.return_value.id = "response-id"
    conn._client = mock_client
    return conn, judged


def test_query_never_classifies_the_deployment_name() -> None:
    """A deployment named after a capable model is not recommended on its spelling.

    The deployment name is chosen by the user and stops tracking the model behind it
    the moment the deployment is repointed, so with no backing model set the answer
    stays merely feasible.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], {"model": "gpt-4o-mini"}
        )
        is NativeStructuredOutputSupport.FEASIBLE
    )


def test_query_does_not_consume_the_backing_model() -> None:
    """The query reads the key that ``chat`` pops, and has to leave it in place.

    Copying the builder's ``pop`` idiom would hand the builder a map with no backing
    model, and the native branch would silently disappear.
    """
    model_kwargs = _model_kwargs("gpt-4o-mini")

    _connection().supports_native_structured_output(
        OutputSchema(output_schema=Person), [], model_kwargs
    )

    assert model_kwargs == _model_kwargs("gpt-4o-mini")


@pytest.mark.parametrize(
    "backing_model",
    ["gpt-4o-mini", "some-unknown-model", None],
    ids=["capable", "unknown", "unset"],
)
def test_query_judges_the_model_the_request_judges(
    backing_model: str | None,
) -> None:
    """The query asks about exactly the model the request path asks about.

    The query resolves the effective model separately from the builder, so only
    capturing what each feeds the capability check keeps the two from drifting apart;
    comparing each against a literal would let them drift in step.
    """
    conn, judged = _judging_connection()
    model_kwargs = _model_kwargs(backing_model)
    schema = OutputSchema(output_schema=Person)

    conn.supports_native_structured_output(schema, [], model_kwargs)
    conn.chat(
        [ChatMessage.of(role=MessageRole.USER, content="hi")],
        output_schema=schema,
        **model_kwargs,
    )

    assert len(judged) == 2
    assert judged[0] == judged[1]
    assert judged[0] != DEPLOYMENT


def test_query_agrees_with_the_native_branch() -> None:
    """The answer matches whether the request ends up carrying a response_format.

    Comparing the answer against what the request carries, rather than against a
    literal, is what keeps the query and the branch from drifting in step. The backing
    model is capable throughout, so the api-version and the schema form are what move.
    """
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    model_kwargs = _model_kwargs("gpt-4o-mini")

    for api_version in (CAPABLE_API_VERSION, BELOW_FLOOR_API_VERSION):
        conn = _connection(api_version)

        for schema in (
            OutputSchema(output_schema=Person),
            OutputSchema(output_schema=ROW_TYPE),
            None,
        ):
            for tools in (None, [], [tool]):
                support = conn.supports_native_structured_output(
                    schema, tools, model_kwargs
                )

                conn.chat(
                    [ChatMessage.of(role=MessageRole.USER, content="hi")],
                    tools=tools,
                    output_schema=schema,
                    **model_kwargs,
                )

                carried = "response_format" in _create_call_kwargs(conn)
                expected = (
                    NativeStructuredOutputSupport.NATIVE_RECOMMENDED
                    if carried
                    else NativeStructuredOutputSupport.INFEASIBLE
                )
                assert support is expected, (
                    f"api-version {api_version}, schema {schema}, tools {tools}"
                )


def test_query_follows_the_api_version_floor() -> None:
    """The configured api-version is part of the answer, not only of the branch.

    Pinning the answer itself rather than only its agreement with the branch: an
    override that dropped this term would drop it from the branch too, and the
    agreement test above would still see the two agree.
    """
    schema = OutputSchema(output_schema=Person)
    params = _model_kwargs("gpt-4o-mini")

    assert (
        _connection(CAPABLE_API_VERSION).supports_native_structured_output(
            schema, [], params
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )
    assert (
        _connection(BELOW_FLOOR_API_VERSION).supports_native_structured_output(
            schema, [], params
        )
        is NativeStructuredOutputSupport.INFEASIBLE
    )


@pytest.mark.parametrize("in_additional_kwargs", [True, False])
def test_query_ignores_a_caller_response_format(
    in_additional_kwargs: bool,
) -> None:
    """A caller-supplied response_format does not make a translatable schema infeasible.

    The branch answers that conflict by raising rather than by skipping, so the query
    has to keep recommending native here. Reporting it infeasible instead would turn a
    documented error into a silently unconstrained request.
    """
    params = _model_kwargs("gpt-4o-mini")
    if in_additional_kwargs:
        params["additional_kwargs"] = {"response_format": CALLER_RESPONSE_FORMAT}
    else:
        params["response_format"] = CALLER_RESPONSE_FORMAT

    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], params
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )


def test_feasibility_is_asked_with_the_unstripped_kwargs() -> None:
    """Feasibility sees the parameters as they arrived, not a copy ``chat`` stripped.

    ``chat`` removes ``model``, ``model_of_azure_deployment`` and ``additional_kwargs``
    from its own mapping before the native branch runs. Asked with that copy, an
    override reading any of them would answer about a request other than the one being
    built. No term of today's answer reads them, so this pins the shape rather than a
    live defect.
    """
    asked: List[Mapping[str, Any] | None] = []

    class _CapturingConnection(AzureOpenAIChatModelConnection):
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

    conn = _CapturingConnection(
        api_key="test-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version=CAPABLE_API_VERSION,
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_message.refusal = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message, finish_reason="stop")
    ]
    mock_client.chat.completions.create.return_value.usage = None
    mock_client.chat.completions.create.return_value.model = "gpt-4o-mini"
    mock_client.chat.completions.create.return_value.id = "test-response"
    conn._client = mock_client

    conn.chat(
        [ChatMessage.of(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        additional_kwargs={"user": "someone"},
        output_schema=OutputSchema(output_schema=Person),
    )

    assert len(asked) == 1
    assert asked[0] is not None
    assert asked[0]["model"] == DEPLOYMENT
    assert asked[0]["model_of_azure_deployment"] == "gpt-4o-mini"
    assert asked[0]["additional_kwargs"] == {"user": "someone"}
