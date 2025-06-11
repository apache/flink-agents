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
import pytest

from flink_agents.api.event import InputEvent, OutputEvent
from flink_agents.plan.action import Action
from flink_agents.plan.function import PythonFunction


def increment(event: InputEvent) -> OutputEvent: # noqa: D103
    value = event.input
    value += 1
    return OutputEvent(output=value)

def decrement(value: int) -> OutputEvent: # noqa: D103
    value -= 1
    return OutputEvent(output=value)

def test_action_signature() -> None: # noqa: D103
    Action(
        name="increment",
        exec=PythonFunction.from_callable(increment),
        listen_event_types=[InputEvent],
    )

    with pytest.raises(TypeError):
        Action(
            name="decrement",
            exec=PythonFunction.from_callable(decrement),
            listen_event_types=[InputEvent],
        )

