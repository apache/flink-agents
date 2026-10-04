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
################################################################################
import array
import base64
import os
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

import pytest
from openai import NOT_GIVEN
from pydantic import ValidationError

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup,
)

test_model = os.environ.get("TEST_EMBEDDING_MODEL", "text-embedding-3-small")
api_key = os.environ.get("TEST_API_KEY")


@pytest.mark.integration
@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_embedding_model() -> None:
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key=api_key)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    embedding_model = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", resource_context=mock_ctx
    )
    embedding_model.open()

    response = embedding_model.embed("Hello, Flink Agent!")
    assert response is not None
    assert isinstance(response, list)
    assert len(response) > 0
    assert all(isinstance(x, float) for x in response)  #


def test_openai_embedding_model_returns_token_usage() -> None:
    """Test OpenAI embedding usage is returned with the embedding result."""
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    mock_client = MagicMock()
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(embedding=[0.1, 0.2, 0.3])],
        usage=SimpleNamespace(prompt_tokens=5, total_tokens=5),
    )
    connection._OpenAIEmbeddingModelConnection__client = mock_client

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource
    embedding_model = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", resource_context=mock_ctx
    )
    embedding_model.open()

    result = embedding_model.embed_with_usage("Hello, Flink Agent!")
    assert result.embeddings == [0.1, 0.2, 0.3]
    assert result.token_usage is not None
    assert result.token_usage.prompt_tokens == 5
    assert result.token_usage.total_tokens == 5


def test_openai_embedding_connection_zero_timeout_disables_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_openai(**kwargs: Any) -> object:
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAI",
        fake_openai,
    )
    zero = OpenAIEmbeddingModelConnection(
        name="openai", api_key="k", request_timeout=0, base_url=" ", organization=" "
    )
    zero.client
    assert captured["timeout"] is None
    # Whitespace-only strings mean the default / absent, as in Java.
    assert captured["base_url"] == "https://api.openai.com/v1"
    assert captured["organization"] is None

    captured.clear()
    ten = OpenAIEmbeddingModelConnection(name="openai", api_key="k", request_timeout=10)
    ten.client
    assert captured["timeout"] == 10


def test_openai_embedding_connection_rejects_invalid_timeout() -> None:
    with pytest.raises(ValidationError):
        OpenAIEmbeddingModelConnection(name="openai", api_key="k", request_timeout=-1)
    with pytest.raises(ValidationError):
        OpenAIEmbeddingModelConnection(name="openai", api_key="k", max_retries=-1)
    with pytest.raises(ValidationError):
        OpenAIEmbeddingModelConnection(name="openai", api_key="k", max_retries=2**31)
    # A blank api_key stays accepted: unauthenticated OpenAI-compatible servers rely on
    # it and the SDK then omits the Authorization header.
    assert OpenAIEmbeddingModelConnection(name="openai", api_key="").api_key == ""


def _setup_with_mock_client(
    additional_kwargs: dict[str, Any],
) -> tuple[OpenAIEmbeddingModelSetup, MagicMock]:
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    mock_client = MagicMock()
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(embedding=[0.1, 0.2, 0.3])],
        usage=SimpleNamespace(prompt_tokens=5, total_tokens=5),
    )
    connection._OpenAIEmbeddingModelConnection__client = mock_client
    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = lambda name, type: connection
    setup = OpenAIEmbeddingModelSetup(
        name="openai",
        model=test_model,
        connection="openai",
        dimensions=3,
        additional_kwargs=additional_kwargs,
        resource_context=mock_ctx,
    )
    setup.open()
    return setup, mock_client


def test_openai_embedding_additional_kwargs_sent_as_extra_body() -> None:
    setup, mock_client = _setup_with_mock_client({"custom_flag": True, "skip": None})

    setup.embed("Hello, Flink Agent!")

    kwargs = mock_client.embeddings.create.call_args.kwargs
    assert kwargs["model"] == test_model
    assert kwargs["dimensions"] == 3
    assert kwargs["extra_body"] == {"custom_flag": True}

    setup_without_extras, mock_client = _setup_with_mock_client({})
    setup_without_extras.embed("Hello, Flink Agent!")
    assert mock_client.embeddings.create.call_args.kwargs["extra_body"] is None


def test_openai_embedding_additional_kwargs_rejects_typed_fields() -> None:
    with pytest.raises(ValidationError, match="typed request fields"):
        OpenAIEmbeddingModelSetup(
            name="openai",
            model=test_model,
            connection="openai",
            additional_kwargs={"dimensions": 8, "custom": 1},
        )
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    with pytest.raises(ValueError, match=r"\['model'\]"):
        connection.embed("x", model=test_model, additional_kwargs={"model": "other"})
    with pytest.raises(TypeError, match="must be a map"):
        connection.embed("x", model=test_model, additional_kwargs="foo")
    with pytest.raises(ValueError, match="empty key"):
        connection.embed("x", model=test_model, additional_kwargs={" ": 1})
    with pytest.raises(ValueError, match="empty key"):
        connection.embed("x", model=test_model, additional_kwargs={None: 1})
    with pytest.raises(TypeError, match="must be a map"):
        connection.embed("x", model=test_model, additional_kwargs=[])
    with pytest.raises(ValidationError, match="empty key"):
        OpenAIEmbeddingModelSetup(
            name="openai",
            model=test_model,
            connection="openai",
            additional_kwargs={"": 1},
        )
    # Non-string keys are stringified as in Java; a non-mapping is a ValidationError.
    int_key = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", additional_kwargs={1: "x"}
    )
    assert int_key.model_kwargs["additional_kwargs"] == {"1": "x"}
    with pytest.raises(ValidationError, match="must be a map"):
        OpenAIEmbeddingModelSetup(
            name="openai",
            model=test_model,
            connection="openai",
            additional_kwargs="foo",
        )
    blank_user = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", user=" "
    )
    assert "user" not in blank_user.model_kwargs


def test_openai_embedding_setup_validates_encoding_format_and_dimensions() -> None:
    with pytest.raises(ValidationError, match="encoding_format"):
        OpenAIEmbeddingModelSetup(
            name="openai", model=test_model, connection="openai", encoding_format="hex"
        )
    # A null (YAML ``~``) or blank encoding_format means the default, as in Java.
    for absent in (None, " "):
        setup = OpenAIEmbeddingModelSetup(
            name="openai", model=test_model, connection="openai", encoding_format=absent
        )
        assert setup.encoding_format == "float"
    with pytest.raises(ValidationError):
        OpenAIEmbeddingModelSetup(
            name="openai", model=test_model, connection="openai", dimensions=0
        )
    for bad in (True, "256"):
        with pytest.raises(ValidationError, match="dimensions"):
            OpenAIEmbeddingModelSetup(
                name="openai", model=test_model, connection="openai", dimensions=bad
            )
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    with pytest.raises(ValueError, match="encoding_format"):
        connection.embed("x", model=test_model, encoding_format="FLOAT")
    with pytest.raises(ValueError, match="dimensions"):
        connection.embed("x", model=test_model, dimensions=-1)
    with pytest.raises(ValueError, match="dimensions"):
        connection.embed("x", model=test_model, dimensions="256")
    with pytest.raises(ValueError, match="dimensions"):
        connection.embed("x", model=test_model, dimensions=True)
    with pytest.raises(ValueError, match="dimensions"):
        connection.embed("x", model=test_model, dimensions=2**40)
    with pytest.raises(ValueError, match="'model'"):
        connection.embed("x", model=" ")
    with pytest.raises(TypeError, match="user must be a string"):
        connection.embed("x", model=test_model, user=12345)
    with pytest.raises(TypeError, match="encoding_format must be a string"):
        connection.embed("x", model=test_model, encoding_format=1)
    with pytest.raises(ValidationError, match="model"):
        OpenAIEmbeddingModelSetup(name="openai", model="", connection="openai")
    with pytest.raises(ValidationError, match="connection"):
        OpenAIEmbeddingModelSetup(name="openai", model=test_model, connection=" ")
    # A connection object (not a name) is accepted, as in the sibling setups.
    setup_with_object = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection=connection
    )
    assert setup_with_object.connection is connection
    for absent in (None, " "):
        conn = OpenAIEmbeddingModelConnection(
            name="openai", api_key="k", base_url=absent
        )
        assert conn.base_url == "https://api.openai.com/v1"
    null_defaults = OpenAIEmbeddingModelConnection(
        name="openai", api_key="k", request_timeout=None, max_retries=None
    )
    assert (null_defaults.request_timeout, null_defaults.max_retries) == (30.0, 3)
    with pytest.raises(ValidationError, match="base_url must be a string"):
        OpenAIEmbeddingModelConnection(name="openai", api_key="k", base_url=8080)
    with pytest.raises(TypeError, match="got: None"):
        connection.embed(None, model=test_model)
    with pytest.raises(TypeError, match="got: dict"):
        connection.embed({"id": "doc-1"}, model=test_model)
    with pytest.raises(ValidationError, match="encoding_format must be a string"):
        OpenAIEmbeddingModelSetup(
            name="openai", model=test_model, connection="openai", encoding_format=1
        )
    with pytest.raises(TypeError, match="Text at index 1 is not a string"):
        connection.embed(["ok", None], model=test_model)
    # An integral float is accepted as in Java.
    setup = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", dimensions=256.0
    )
    assert setup.model_kwargs["dimensions"] == 256


def test_openai_embedding_blank_strings_and_default_format() -> None:
    connection, mock_client = _connection_returning([SimpleNamespace(embedding=[0.1])])

    connection.embed("x", model=test_model, encoding_format=" ", user=" ")

    kwargs = mock_client.embeddings.create.call_args.kwargs
    # Blank user is omitted and a blank encoding_format means the default, as in Java.
    assert kwargs["user"] is NOT_GIVEN
    assert kwargs["encoding_format"] == "float"

    connection.embed("x", model=test_model, encoding_format="base64", user="u1")
    kwargs = mock_client.embeddings.create.call_args.kwargs
    assert kwargs["user"] == "u1"
    assert kwargs["encoding_format"] == "base64"


def _connection_returning(
    data: list[Any] | None,
) -> tuple[OpenAIEmbeddingModelConnection, MagicMock]:
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    mock_client = MagicMock()
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=data, usage=SimpleNamespace(prompt_tokens=None, total_tokens=7)
    )
    connection._OpenAIEmbeddingModelConnection__client = mock_client
    return connection, mock_client


def test_openai_embedding_decodes_base64_and_orders_by_index() -> None:
    encoded = base64.b64encode(array.array("f", [0.5, 0.25]).tobytes()).decode()
    connection, _ = _connection_returning(
        [
            SimpleNamespace(index=1, embedding=[0.9]),
            SimpleNamespace(index=0, embedding=encoded),
        ]
    )

    result = connection.embed_with_usage(
        ["a", "b"], model=test_model, encoding_format="base64"
    )

    assert result.embeddings == [[0.5, 0.25], [0.9]]

    # Unpadded base64 (one float, "AACAPw" instead of "AACAPw==") decodes as in Java.
    unpadded, _ = _connection_returning([SimpleNamespace(index=0, embedding="AACAPw")])
    assert unpadded.embed("a", model=test_model, encoding_format="base64") == [1.0]
    # Partial padding ("AACAPw=" for "AACAPw==") is rejected, as by Java's decoder.
    partial, _ = _connection_returning([SimpleNamespace(index=0, embedding="AACAPw=")])
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        partial.embed("a", model=test_model, encoding_format="base64")
    # A missing prompt_tokens equals total_tokens for embedding requests.
    assert result.token_usage is not None
    assert result.token_usage.prompt_tokens == 7
    assert result.token_usage.total_tokens == 7


def test_openai_embedding_rejects_inconsistent_responses() -> None:
    short, _ = _connection_returning([SimpleNamespace(index=0, embedding=[0.1])])
    with pytest.raises(RuntimeError, match="1 embeddings for 2 input texts"):
        short.embed(["a", "b"], model=test_model)

    contradictory, _ = _connection_returning(
        [SimpleNamespace(embedding=[0.1]), SimpleNamespace(index=0, embedding=[0.2])]
    )
    with pytest.raises(
        RuntimeError, match="unexpected embedding index 0 at position 1"
    ):
        contradictory.embed(["a", "b"], model=test_model)

    duplicate, _ = _connection_returning(
        [
            SimpleNamespace(index=0, embedding=[0.1]),
            SimpleNamespace(index=0, embedding=[0.2]),
        ]
    )
    with pytest.raises(RuntimeError, match="unexpected embedding index"):
        duplicate.embed(["a", "b"], model=test_model)
    string_index, _ = _connection_returning(
        [
            SimpleNamespace(index="1", embedding=[0.1]),
            SimpleNamespace(index="0", embedding=[0.2]),
        ]
    )
    with pytest.raises(
        RuntimeError, match="non-integer embedding index '1' at position 0"
    ):
        string_index.embed(["a", "b"], model=test_model)
    float_index, _ = _connection_returning(
        [
            SimpleNamespace(index=1.0, embedding=[0.1]),
            SimpleNamespace(index=0.0, embedding=[0.2]),
        ]
    )
    assert float_index.embed(["a", "b"], model=test_model) == [[0.2], [0.1]]

    malformed, _ = _connection_returning(
        [SimpleNamespace(index=0, embedding="AACAPwAA")]
    )
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        malformed.embed("a", model=test_model, encoding_format="base64")
    corrupted, _ = _connection_returning([SimpleNamespace(index=0, embedding="@@@@")])
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        corrupted.embed("a", model=test_model, encoding_format="base64")
    strings, _ = _connection_returning(
        [SimpleNamespace(index=0, embedding=["0.1", "0.2"])]
    )
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        strings.embed("a", model=test_model)
    # A string vector is only decoded when base64 was requested.
    unexpected_b64, _ = _connection_returning(
        [SimpleNamespace(index=0, embedding="AAAAAAAAAAA=")]
    )
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        unexpected_b64.embed("a", model=test_model)
    null_vector, _ = _connection_returning([SimpleNamespace(index=0, embedding=None)])
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        null_vector.embed("a", model=test_model)
    no_data, _ = _connection_returning(None)
    with pytest.raises(RuntimeError, match="0 embeddings for 1 input texts"):
        no_data.embed("a", model=test_model)
    null_item, _ = _connection_returning([None])
    with pytest.raises(RuntimeError, match="malformed embedding at position 0"):
        null_item.embed("a", model=test_model)


def test_openai_embedding_empty_batch_skips_request() -> None:
    connection, mock_client = _connection_returning([])
    result = connection.embed_with_usage([], model=test_model)
    assert result.embeddings == []
    assert result.token_usage is None
    mock_client.embeddings.create.assert_not_called()

    # A one-shot iterable is materialized before validation and sent as a list.
    connection, mock_client = _connection_returning(
        [
            SimpleNamespace(index=0, embedding=[0.1]),
            SimpleNamespace(index=1, embedding=[0.2]),
        ]
    )
    result = connection.embed_with_usage((t for t in ["a", "b"]), model=test_model)
    assert result.embeddings == [[0.1], [0.2]]
    assert mock_client.embeddings.create.call_args.kwargs["input"] == ["a", "b"]


def test_openai_embedding_unusable_usage_yields_none() -> None:
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    mock_client = MagicMock()
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(index=0, embedding=[0.1])],
        usage=SimpleNamespace(prompt_tokens="n/a", total_tokens=None),
    )
    connection._OpenAIEmbeddingModelConnection__client = mock_client

    result = connection.embed_with_usage("a", model=test_model)

    assert result.embeddings == [0.1]
    assert result.token_usage is None

    # A huge integer count is passed through rather than overflowing a float.
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(index=0, embedding=[0.1])],
        usage=SimpleNamespace(prompt_tokens=10**400, total_tokens=None),
    )
    assert (
        connection.embed_with_usage("a", model=test_model).token_usage.prompt_tokens
        == 10**400
    )

    # Numeric strings, which some compatible servers send, still count.
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(index=0, embedding=[0.1])],
        usage=SimpleNamespace(prompt_tokens="5", total_tokens="5"),
    )
    result = connection.embed_with_usage("a", model=test_model)
    assert result.token_usage is not None
    assert (result.token_usage.prompt_tokens, result.token_usage.total_tokens) == (5, 5)
