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
"""Concurrent first calls of VectorStoreSearchTool through a real ResourceCache."""

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Dict, List

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.vector_store_search_tool import VectorStoreSearchTool
from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStore,
    Document,
    VectorStoreQuery,
    VectorStoreQueryResult,
)
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import ResourceProvider
from flink_agents.runtime.resource_cache import ResourceCache


class _Lifecycle:
    """Counts store opens and closes across instances."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.opened = 0
        self.closed = 0


class _CountingStore(BaseVectorStore):
    """Vector store whose slow ``open()`` widens the initialization window."""

    lifecycle: Any

    @property
    def store_kwargs(self) -> Dict[str, Any]:
        return {}

    def open(self) -> None:
        with self.lifecycle.lock:
            self.lifecycle.opened += 1
        time.sleep(0.2)

    def close(self) -> None:
        with self.lifecycle.lock:
            self.lifecycle.closed += 1

    def query(self, query: VectorStoreQuery) -> VectorStoreQueryResult:
        return VectorStoreQueryResult(documents=[Document(content=query.query_text)])

    def get(self, *args: Any, **kwargs: Any) -> List[Document]:
        raise NotImplementedError

    def delete(self, *args: Any, **kwargs: Any) -> None:
        raise NotImplementedError

    def _query_embedding(self, *args: Any, **kwargs: Any) -> List[Document]:
        raise NotImplementedError

    def _add_embedding(self, *args: Any, **kwargs: Any) -> List[str]:
        raise NotImplementedError

    def _update_embedding(self, *args: Any, **kwargs: Any) -> None:
        raise NotImplementedError


class _FactoryProvider(ResourceProvider):
    """Builds a fresh resource on every ``provide()``, like a descriptor does."""

    factory: Callable[[ResourceContext], Resource]

    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        return self.factory(resource_context)


def test_concurrent_first_calls_open_vector_store_once() -> None:
    lifecycle = _Lifecycle()
    cache = ResourceCache(
        {
            ResourceType.TOOL: {
                "search_docs": _FactoryProvider(
                    name="search_docs",
                    type=ResourceType.TOOL,
                    factory=lambda ctx: VectorStoreSearchTool(
                        name="search_docs",
                        description="Search the docs.",
                        vector_store="knowledge_base",
                        resource_context=ctx,
                    ),
                )
            },
            ResourceType.VECTOR_STORE: {
                "knowledge_base": _FactoryProvider(
                    name="knowledge_base",
                    type=ResourceType.VECTOR_STORE,
                    factory=lambda ctx: _CountingStore(
                        lifecycle=lifecycle, resource_context=ctx
                    ),
                )
            },
        }
    )
    tool = cache.get_resource("search_docs", ResourceType.TOOL)
    barrier = threading.Barrier(2)

    def search(query: str) -> Any:
        barrier.wait()
        return tool.call(query=query)

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(search, ["checkpoint", "watermark"]))
    cache.close()

    assert results == [
        [{"content": "checkpoint", "metadata": {}}],
        [{"content": "watermark", "metadata": {}}],
    ]
    assert lifecycle.opened == 1
    assert lifecycle.closed == 1
