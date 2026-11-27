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
"""Retry statistics of a chat request, kept across its tool-call rounds."""

from uuid import UUID

from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.runner_context import RunnerContext

# Sensory-memory key; checkpointed state, so the name must not change.
RETRY_STATS_CONTEXT = "_RETRY_STATS_CONTEXT"


def accumulate(
    sensory_memory: MemoryObject,
    initial_request_id: UUID,
    retry_count: int,
    retry_wait_ms: int,
) -> None:
    """Accumulate retry stats for a given initial request across tool call rounds."""
    key = str(initial_request_id)
    retry_stats_context = sensory_memory.get(RETRY_STATS_CONTEXT) or {}
    stats = retry_stats_context.get(
        key,
        {
            "total_retry_count": 0,
            "total_retry_wait_ms": 0,
        },
    )
    stats["total_retry_count"] += retry_count
    stats["total_retry_wait_ms"] += retry_wait_ms
    retry_stats_context[key] = stats
    sensory_memory.set(RETRY_STATS_CONTEXT, retry_stats_context)


def get(sensory_memory: MemoryObject, initial_request_id: UUID) -> dict:
    """Get accumulated retry stats for a given initial request."""
    retry_stats_context = sensory_memory.get(RETRY_STATS_CONTEXT) or {}
    return retry_stats_context.get(
        str(initial_request_id),
        {
            "total_retry_count": 0,
            "total_retry_wait_ms": 0,
        },
    )


def record_metrics(
    ctx: RunnerContext,
    model_resource: str,
    retry_count: int,
    total_retry_wait_ms: int,
) -> None:
    """Record retry metrics under the ChatModel resource if retries occurred."""
    if retry_count <= 0:
        return
    metric_group = ctx.action_metric_group
    if metric_group is not None:
        model_resource_group = metric_group.get_sub_group(
            "model_resource", model_resource
        )
        model_resource_group.get_counter("retryCount").inc(retry_count)
        model_resource_group.get_counter("retryWaitMs").inc(total_retry_wait_ms)
