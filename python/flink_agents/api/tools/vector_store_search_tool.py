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
"""Built-in tool that lets an agent search a registered vector store."""

import base64
import datetime
import math
from typing import Any, Dict, List

from pydantic import BaseModel, Field, PrivateAttr, model_validator
from typing_extensions import override

from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStore,
    VectorStoreQuery,
)

NO_RESULTS_MESSAGE = "No relevant documents found."


class VectorStoreSearchArgs(BaseModel):
    """Arguments of VectorStoreSearchTool that are visible to the model."""

    query: str = Field(
        ...,
        description="Natural-language query describing the information to find.",
    )


class VectorStoreSearchTool(Tool):
    """Search a vector store declared in the same agent.

    The model passes only ``query``. The tool returns a list of
    ``{"content": ..., "metadata": ...}`` entries, with metadata converted to
    JSON-compatible values, or ``"No relevant documents found."``. Errors from
    the vector store are raised, not returned.

    Register the tool under the same name as its ``name`` argument::

        agents_env.add_resource(
            "search_flink_docs",
            ResourceType.TOOL,
            VectorStoreSearchTool(
                name="search_flink_docs",
                description="Search the Flink documentation.",
                vector_store="knowledge_base",
                max_results=3,
            ),
        )

    Attributes:
    ----------
    vector_store : str
        Name of the vector store resource to search.
    max_results : int
        Maximum number of documents returned per call. Must be positive.
    """

    vector_store: str
    max_results: int = Field(default=3, gt=0)
    _resolved_store: BaseVectorStore | None = PrivateAttr(default=None)

    @model_validator(mode="before")
    @classmethod
    def _build_metadata(cls, data: Any) -> Any:
        """Build the tool metadata from ``name`` and ``description``.

        A serialized tool already carries ``metadata`` and is passed through.
        """
        if not isinstance(data, dict):
            return data
        if "metadata" in data:
            if "name" in data or "description" in data:
                msg = (
                    "VectorStoreSearchTool accepts either 'metadata' or "
                    "'name' and 'description', not both."
                )
                raise ValueError(msg)
            return data
        missing = [field for field in ("name", "description") if field not in data]
        if missing:
            msg = f"VectorStoreSearchTool requires {' and '.join(missing)}."
            raise ValueError(msg)
        data = dict(data)
        data["metadata"] = ToolMetadata(
            name=data.pop("name"),
            description=data.pop("description"),
            args_schema=VectorStoreSearchArgs,
        )
        return data

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        """Return tool type of class."""
        return ToolType.FUNCTION

    @override
    def open(self) -> None:
        # Calls may run concurrently in async workers; resolve before them.
        if self.resource_context is None:
            msg = "VectorStoreSearchTool requires a ResourceContext."
            raise RuntimeError(msg)
        store = self.resource_context.get_resource(
            self.vector_store, ResourceType.VECTOR_STORE
        )
        if not isinstance(store, BaseVectorStore):
            msg = f"Resource '{self.vector_store}' must be a vector store."
            raise TypeError(msg)
        self._resolved_store = store

    @override
    def call(self, *args: Any, **kwargs: Any) -> List[Dict[str, Any]] | str:
        """Search the vector store and return the matching documents."""
        parsed = (
            VectorStoreSearchArgs(query=args[0], **kwargs)
            if args
            else VectorStoreSearchArgs(**kwargs)
        )
        if self._resolved_store is None:
            msg = (
                f"Vector store '{self.vector_store}' has not been resolved. "
                "Ensure open() is called before calling the tool."
            )
            raise TypeError(msg)
        result = self._resolved_store.query(
            VectorStoreQuery(query_text=parsed.query, limit=self.max_results)
        )
        if not result.documents:
            return NO_RESULTS_MESSAGE
        return [
            {"content": doc.content, "metadata": _to_json_compatible(doc.metadata)}
            for doc in result.documents
        ]


def _to_json_compatible(value: Any) -> Any:
    """Convert a metadata value into a value ``json.dumps`` accepts."""
    if value is None or isinstance(value, str | bool | int):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else str(value)
    if isinstance(value, dict):
        return {str(k): _to_json_compatible(v) for k, v in value.items()}
    if isinstance(value, list | tuple | set):
        return [_to_json_compatible(v) for v in value]
    if isinstance(value, bytes | bytearray):
        return base64.b64encode(value).decode("ascii")
    if isinstance(value, datetime.datetime | datetime.date | datetime.time):
        return value.isoformat()
    if hasattr(value, "dtype") and hasattr(value, "tolist"):
        return _to_json_compatible(value.tolist())
    return str(value)
