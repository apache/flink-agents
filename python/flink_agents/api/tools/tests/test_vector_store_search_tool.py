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
import datetime
import json
from typing import Any, Dict, List
from unittest.mock import MagicMock

import pytest
from pydantic import ValidationError

from flink_agents.api.resource import ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.vector_store_search_tool import (
    NO_RESULTS_MESSAGE,
    VectorStoreSearchTool,
)
from flink_agents.api.vector_stores.vector_store import (
    Document,
    VectorStoreQueryResult,
)


def _make_tool(
    documents: List[Document], **kwargs: Any
) -> tuple[VectorStoreSearchTool, MagicMock, MagicMock]:
    store = MagicMock()
    store.query.return_value = VectorStoreQueryResult(documents=documents)
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource.return_value = store
    args: Dict[str, Any] = {
        "name": "search_docs",
        "description": "Search the docs.",
        "vector_store": "knowledge_base",
        "resource_context": ctx,
    }
    args.update(kwargs)
    return VectorStoreSearchTool(**args), store, ctx


def test_call_returns_content_and_metadata_only() -> None:
    tool, _, _ = _make_tool(
        [
            Document(
                content="Checkpoints recover state.",
                metadata={"source": "checkpoints.md"},
                id="doc-1",
                embedding=[0.1, 0.2],
                score=0.5,
            ),
            Document(content="Watermarks track event time."),
        ]
    )

    assert tool.call(query="recovery") == [
        {
            "content": "Checkpoints recover state.",
            "metadata": {"source": "checkpoints.md"},
        },
        {"content": "Watermarks track event time.", "metadata": {}},
    ]


def test_call_returns_message_when_nothing_matches() -> None:
    tool, _, _ = _make_tool([])

    assert (
        tool.call(query="anything")
        == NO_RESULTS_MESSAGE
        == ("No relevant documents found.")
    )


def test_call_queries_with_query_text_and_max_results() -> None:
    tool, store, _ = _make_tool([], max_results=5)

    tool.call(query="event time")

    store.query.assert_called_once()
    query = store.query.call_args.args[0]
    assert query.query_text == "event time"
    assert query.limit == 5


def test_max_results_defaults_to_three() -> None:
    tool, store, _ = _make_tool([])

    tool.call(query="event time")

    assert store.query.call_args.args[0].limit == 3


def test_call_looks_up_vector_store_by_name() -> None:
    tool, _, ctx = _make_tool([])

    tool.call(query="event time")

    ctx.get_resource.assert_called_once_with(
        "knowledge_base", ResourceType.VECTOR_STORE
    )


def test_call_propagates_vector_store_errors() -> None:
    tool, store, _ = _make_tool([])
    store.query.side_effect = ConnectionError("vector store unavailable")

    with pytest.raises(ConnectionError, match="vector store unavailable"):
        tool.call(query="event time")


def test_metadata_exposes_only_query_argument() -> None:
    tool, _, _ = _make_tool([])

    assert tool.name == "search_docs"
    assert tool.metadata.description == "Search the docs."
    schema = tool.metadata.args_schema
    assert list(schema["properties"]) == ["query"]
    assert schema["properties"]["query"]["type"] == "string"
    assert schema["required"] == ["query"]


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"description": "d", "vector_store": "kb"}, "requires name"),
        ({"name": "n", "vector_store": "kb"}, "requires description"),
        ({"vector_store": "kb"}, "requires name and description"),
        ({"name": "n", "description": "d"}, "vector_store"),
        (
            {"name": "n", "description": "d", "vector_store": "kb", "max_results": 0},
            "greater than 0",
        ),
    ],
)
def test_rejects_invalid_arguments(kwargs: Dict[str, Any], message: str) -> None:
    with pytest.raises(ValidationError, match=message):
        VectorStoreSearchTool(**kwargs)


@pytest.mark.parametrize("conflicting", [{"name": "other"}, {"description": "other"}])
def test_rejects_metadata_with_name_or_description(
    conflicting: Dict[str, Any],
) -> None:
    tool, _, _ = _make_tool([])

    with pytest.raises(ValidationError, match="not both"):
        VectorStoreSearchTool(
            metadata=tool.metadata, vector_store="knowledge_base", **conflicting
        )


def test_validates_from_dumped_fields() -> None:
    tool, _, _ = _make_tool([], max_results=5)
    dumped = tool.model_dump()

    restored = VectorStoreSearchTool.model_validate(dumped)

    assert restored.metadata == tool.metadata
    assert restored.vector_store == "knowledge_base"
    assert restored.max_results == 5
    assert "name" not in dumped


def test_does_not_mutate_input_dict() -> None:
    data = {"name": "n", "description": "d", "vector_store": "kb"}

    VectorStoreSearchTool.model_validate(data)

    assert data == {"name": "n", "description": "d", "vector_store": "kb"}


def test_validates_existing_instance() -> None:
    # Pydantic reuses an instance of the same class without running
    # validators, so the non-dict branch of the validator is defensive.
    tool, _, _ = _make_tool([])

    assert VectorStoreSearchTool.model_validate(tool) is tool


class _Custom:
    def __str__(self) -> str:
        return "custom"


def test_call_converts_metadata_to_json_compatible_values() -> None:
    tool, _, _ = _make_tool(
        [
            Document(
                content="c",
                metadata={
                    "ingested_at": datetime.datetime(2026, 11, 14, 9, 30),
                    "day": datetime.date(2026, 11, 14),
                    "raw": b"\x00\x01",
                    "nan": float("nan"),
                    "inf": float("inf"),
                    "ratio": 0.5,
                    "nested": {1: ("a", {"b"}), "flag": True, "none": None},
                    "custom": _Custom(),
                },
            )
        ]
    )

    metadata = tool.call(query="q")[0]["metadata"]

    assert metadata == {
        "ingested_at": "2026-11-14T09:30:00",
        "day": "2026-11-14",
        "raw": "AAE=",
        "nan": "nan",
        "inf": "inf",
        "ratio": 0.5,
        "nested": {"1": ["a", ["b"]], "flag": True, "none": None},
        "custom": "custom",
    }
    json.dumps(metadata, allow_nan=False)


def test_call_converts_numpy_metadata_to_native_values() -> None:
    np = pytest.importorskip("numpy")
    tool, _, _ = _make_tool(
        [
            Document(
                content="c",
                metadata={
                    "count": np.int64(5),
                    "flag": np.bool_(True),
                    "ratio": np.float32(0.5),
                    "vector": np.array([1, 2]),
                },
            )
        ]
    )

    metadata = tool.call(query="q")[0]["metadata"]

    assert metadata == {"count": 5, "flag": True, "ratio": 0.5, "vector": [1, 2]}
    assert type(metadata["count"]) is int
    assert type(metadata["flag"]) is bool
    json.dumps(metadata, allow_nan=False)
