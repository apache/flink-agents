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

import cloudpickle

from flink_agents.api.decorators import tool
from flink_agents.api.embedding_models.embedding_model import (
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.api.events.event import Event
from flink_agents.api.tools import InjectedArg, ToolResponse
from flink_agents.runtime.python_java_utils import (
    call_embedding_with_usage,
    convert_to_python_key_text,
    get_python_tool_metadata,
    invoke_python_tool,
    wrap_to_input_event,
)


@tool(injected_args={"tenant_id": InjectedArg.from_config("tenant.id")})
def decorated_python_tool(order_id: str, tenant_id: str, request_id: str) -> str:
    """Query order."""
    return f"{tenant_id}:{request_id}:{order_id}"


def raw_python_tool(value: str) -> dict[str, object]:
    return {"__flink_agents_tool_result__": "response", "value": value}


def failed_python_tool(value: str) -> ToolResponse:
    return ToolResponse.error(value, execution_time_ms=7, tool_name="failed")


def test_get_python_tool_metadata_merges_callable_injected_args() -> None:
    flat = get_python_tool_metadata(
        __name__, "decorated_python_tool", injected_args=["request_id"]
    )

    schema = json.loads(flat["inputSchema"])
    assert set(schema["properties"]) == {"order_id"}
    injected_args = json.loads(flat["injectedArgs"])
    assert injected_args == {"tenant_id": {"source": "config", "key": "tenant.id"}}


def test_invoke_python_tool_wraps_raw_payload_without_inspecting_it() -> None:
    result = invoke_python_tool(__name__, "raw_python_tool", {"value": "raw"})

    assert result == {
        "__flink_agents_tool_result__": "raw",
        "result": {"__flink_agents_tool_result__": "response", "value": "raw"},
    }


def test_invoke_python_tool_preserves_explicit_failure() -> None:
    result = invoke_python_tool(__name__, "failed_python_tool", {"value": "failed"})

    assert result == {
        "__flink_agents_tool_result__": "response",
        "result": None,
        "success": False,
        "error": "failed",
        "execution_time_ms": 7,
        "tool_name": "failed",
    }


class _UsageAwareEmbeddingModel:
    def embed_with_usage(
        self, text: str, **kwargs: object
    ) -> EmbeddingResult[list[float]]:
        assert text == "hello"
        assert kwargs == {"model": "test-model"}
        return EmbeddingResult(
            embeddings=[0.1, 0.2],
            token_usage=EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9),
        )


def test_call_embedding_with_usage_returns_pemja_safe_primitives() -> None:
    result = call_embedding_with_usage(
        _UsageAwareEmbeddingModel(), {"text": "hello", "model": "test-model"}
    )

    assert result == {
        "embeddings": [0.1, 0.2],
        "token_usage": {"prompt_tokens": 7, "total_tokens": 9},
    }


def test_wrap_to_input_event_assigns_unique_random_id_per_record() -> None:
    payload = cloudpickle.dumps("same input")

    first = Event.from_json(wrap_to_input_event(payload))
    second = Event.from_json(wrap_to_input_event(payload))

    assert first.id != second.id
    assert first.id.version == 4
    assert second.id.version == 4


def test_convert_to_python_key_text_uses_python_str() -> None:
    assert convert_to_python_key_text(cloudpickle.dumps(7), "pickled") == "7"
    assert (
        convert_to_python_key_text(
            cloudpickle.dumps(("tenant", 7)),
            "pickled",
        )
        == "('tenant', 7)"
    )


def test_convert_to_python_key_text_does_not_unpickle_explicit_bytes() -> None:
    assert convert_to_python_key_text(b"N.", "explicit") == "b'N.'"
    assert convert_to_python_key_text(b"\x80\x04N.", "explicit") == "b'\\x80\\x04N.'"
