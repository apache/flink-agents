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
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.vector_store import VectorStoreQuery
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction


def process_context_retrieval_request(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing context retrieval requests."""
    if isinstance(event, ContextRetrievalRequestEvent):
        vector_store = ctx.get_resource(
            event.vector_store,
            ResourceType.VECTOR_STORE
        )

        query = VectorStoreQuery(
            query_text=event.query,
            limit=event.max_results
        )

        result = vector_store.query(query)

        ctx.send_event(ContextRetrievalResponseEvent(
            request_id=event.id,
            query=event.query,
            documents=result.documents
        ))


CONTEXT_RETRIEVAL_ACTION = Action(
    name="context_retrieval_action",
    exec=PythonFunction.from_callable(process_context_retrieval_request),
    listen_event_types=[
        f"{ContextRetrievalRequestEvent.__module__}.{ContextRetrievalRequestEvent.__name__}",
    ],
)
