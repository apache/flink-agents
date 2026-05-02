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
from pathlib import Path

import pytest
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction


def legal_signature(event: Event, ctx: RunnerContext) -> None:
    pass


def illegal_signature(value: int, ctx: RunnerContext) -> None:
    pass


def test_action_signature_legal() -> None:
    Action(
        name="legal",
        exec=PythonFunction.from_callable(legal_signature),
        listen_event_types=[InputEvent.EVENT_TYPE],
    )


def test_action_signature_illegal() -> None:
    with pytest.raises(TypeError):
        Action(
            name="illegal",
            exec=PythonFunction.from_callable(illegal_signature),
            listen_event_types=[InputEvent.EVENT_TYPE],
        )


@pytest.fixture(scope="module")
def action() -> Action:
    func = PythonFunction.from_callable(legal_signature)
    return Action(
        name="legal",
        exec=func,
        listen_event_types=[InputEvent.EVENT_TYPE],
        config={
            "output_schema": OutputSchema(
                output_schema=RowTypeInfo(
                    [BasicTypeInfo.INT_TYPE_INFO()],
                    ["result"],
                )
            )
        },
    )


current_dir = Path(__file__).parent


def test_action_serialize(action: Action) -> None:
    json_value = action.model_dump_json(serialize_as_any=True, indent=4)
    with Path.open(Path(f"{current_dir}/resources/action.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_action_deserialize(action: Action) -> None:
    with Path.open(Path(f"{current_dir}/resources/action.json")) as f:
        expected_json = f.read()
    action = Action.model_validate_json(expected_json)
    assert action.name == "legal"
    assert action.listen_event_types == ["_input_event"]
    func = action.exec
    assert func.module == "flink_agents.plan.tests.test_action"
    assert func.qualname == "legal_signature"
