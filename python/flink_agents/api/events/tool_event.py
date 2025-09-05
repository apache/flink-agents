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
from typing import Any, Dict, List, Optional
from uuid import UUID

from flink_agents.api.events.event import Event


class ToolRequestEvent(Event):
    """Event representing a tool call request.

    Attributes:
    ----------
    model: str
        name of the model that generated the tool request.
    tool_calls : List[Dict[str, Any]]
        tool calls that should be executed in batch.
    """

    model: str
    tool_calls: List[Dict[str, Any]]


class ToolResponseEvent(Event):
    """Event representing a result from tool call.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    responses : Dict[UUID, Any]
        The dict maps tool call id to result.
    external_ids : Dict[UUID, str]
        Optional identifier for storing original tool call IDs from external systems
        (e.g., Anthropic tool_use_id).
    """

    request_id: UUID
    responses: Dict[UUID, Any]
    external_ids: Dict[UUID, Optional[str]]
