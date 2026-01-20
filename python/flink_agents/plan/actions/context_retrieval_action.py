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
import logging

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.java_vector_store import JavaVectorStore
from flink_agents.api.vector_stores.vector_store import VectorStoreQuery
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction

_logger = logging.getLogger(__name__)

async def process_context_retrieval_request(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing context retrieval requests."""
    if isinstance(event, ContextRetrievalRequestEvent):
        vector_store = ctx.get_resource(event.vector_store, ResourceType.VECTOR_STORE)

        query = VectorStoreQuery(query_text=event.query, limit=event.max_results)

        rag_async = ctx.config.get(AgentExecutionOptions.RAG_ASYNC)
        # java vector store doesn't support async execution
        # see https://github.com/apache/flink-agents/issues/448 for details.
        rag_async = rag_async and not isinstance(vector_store, JavaVectorStore)
        if rag_async:
            # To avoid https://github.com/alibaba/pemja/issues/88,
            # we log a message here.
            _logger.debug("Processing context retrieval asynchronously.")
            result = await ctx.durable_execute_async(vector_store.query, query)
        else:
            result = ctx.durable_execute(vector_store.query, query)

        ctx.send_event(
            ContextRetrievalResponseEvent(
                request_id=event.id, query=event.query, documents=result.documents
            )
        )


CONTEXT_RETRIEVAL_ACTION = Action(
    name="context_retrieval_action",
    exec=PythonFunction.from_callable(process_context_retrieval_request),
    listen_event_types=[
        f"{ContextRetrievalRequestEvent.__module__}.{ContextRetrievalRequestEvent.__name__}",
    ],
)
