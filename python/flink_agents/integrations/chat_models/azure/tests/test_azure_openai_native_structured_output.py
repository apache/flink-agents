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
from typing import Any
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
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
        name="azure_openai",
        api_key="test-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version=api_version,
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message)
    ]
    mock_client.chat.completions.create.return_value.usage = None
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment=model_of_azure_deployment,
        output_schema=None if schema is None else OutputSchema(output_schema=schema),
        **channel,
    )


def test_native_applied_for_capable_deployment_model() -> None:
    """response_format json_schema strict applied for a BaseModel on a capable model."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert _create_call_kwargs(conn)["model"] == DEPLOYMENT


def test_native_not_applied_when_deployment_model_absent() -> None:
    """Native NOT applied when the backing model of the deployment is unknown."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_unknown_deployment_model() -> None:
    """Native NOT applied for a backing model outside the allowlist."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_api_version_below_floor() -> None:
    """Native NOT applied when the configured api-version predates the floor."""
    conn = _connection(api_version=BELOW_FLOOR_API_VERSION)
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_schema_none() -> None:
    """Native NOT applied when no output schema is supplied."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model=DEPLOYMENT,
        model_of_azure_deployment="gpt-4o-mini",
        output_schema=None,
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_row_type_info() -> None:
    """Native NOT applied for a RowTypeInfo schema (BaseModel-only scope)."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
        [ChatMessage(role=MessageRole.USER, content="hi")],
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
def test_capability_predicate_accepts_capable_models(model: str) -> None:
    """The capability predicate accepts every documented capable Azure model name.

    The list is the whole allowlist, so dropping an entry is caught rather than only
    narrowing capability silently.
    """
    assert _connection().supports_native_structured_output(model) is True


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
def test_capability_predicate_rejects_incapable_models(model: str | None) -> None:
    """The capability predicate rejects incapable, Responses-only, and empty names.

    A version-suffixed value such as `gpt-4o-2024-08-06` is an OpenAI snapshot name,
    not a name Azure reports as the model behind a deployment. The codex, `gpt-5-pro`
    and `o3-pro` names do support structured outputs but are served only on the
    Responses API, so they are incapable on the chat completions API this connection
    calls.
    """
    assert _connection().supports_native_structured_output(model) is False


def test_capability_predicate_reads_no_instance_state() -> None:
    """The capability predicate is a pure function of its argument.

    The subclass walk that checks connection capabilities calls this on an instance
    built with `__new__`, where reading any field raises AttributeError.
    """
    uninitialized = AzureOpenAIChatModelConnection.__new__(
        AzureOpenAIChatModelConnection
    )
    assert uninitialized.supports_native_structured_output("gpt-5") is True
