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

from flink_agents.api.event import InputEvent
from flink_agents.plan.action import Action
from flink_agents.plan.function import PythonFunction


def legal_signature(event: InputEvent) -> None: # noqa: D103
    pass

def illegal_signature(value: int) ->  None: # noqa: D103
    pass

def test_action_signature_legal() -> None: # noqa: D103
    Action(
        name="legal",
        exec=PythonFunction.from_callable(legal_signature),
        listen_event_types=[InputEvent],
    )

def test_action_signature_illegal() -> None:  # noqa: D103
    with pytest.raises(TypeError):
        Action(
            name="illegal",
            exec=PythonFunction.from_callable(illegal_signature),
            listen_event_types=[InputEvent],
        )


