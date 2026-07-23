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
from flink_agents.api.events.event import Event
from flink_agents.api.tools import InjectedArg
from flink_agents.runtime.python_java_utils import (
    get_python_tool_metadata,
    wrap_to_input_event,
)


@tool(injected_args={"tenant_id": InjectedArg.from_config("tenant.id")})
def decorated_python_tool(order_id: str, tenant_id: str, request_id: str) -> str:
    """Query order."""
    return f"{tenant_id}:{request_id}:{order_id}"


def test_get_python_tool_metadata_merges_callable_injected_args() -> None:
    flat = get_python_tool_metadata(
        __name__, "decorated_python_tool", injected_args=["request_id"]
    )

    schema = json.loads(flat["inputSchema"])
    assert set(schema["properties"]) == {"order_id"}
    injected_args = json.loads(flat["injectedArgs"])
    assert injected_args == {"tenant_id": {"source": "config", "key": "tenant.id"}}


def test_wrap_to_input_event_assigns_unique_random_id_per_record() -> None:
    payload = cloudpickle.dumps("same input")

    first = Event.from_json(wrap_to_input_event(payload))
    second = Event.from_json(wrap_to_input_event(payload))

    assert first.id != second.id
    assert first.id.version == 4
    assert second.id.version == 4
