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
#  limitations under the License.
################################################################################

from typing import NoReturn

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceType


class _StubExecutionEnvironment(AgentsExecutionEnvironment):
    """Minimal concrete env: add_resource is inherited, the rest is unused here."""

    def get_config(self, *args: object, **kwargs: object) -> NoReturn:
        raise NotImplementedError

    def from_datastream(self, *args: object, **kwargs: object) -> NoReturn:
        raise NotImplementedError

    def from_table(self, *args: object, **kwargs: object) -> NoReturn:
        raise NotImplementedError

    def execute(self, *args: object, **kwargs: object) -> NoReturn:
        raise NotImplementedError


@pytest.mark.parametrize(
    "registrar_type",
    [Agent, _StubExecutionEnvironment],
    ids=["agent", "execution_environment"],
)
def test_python_model_router_registration_is_an_explicit_error(
    registrar_type: type,
) -> None:
    """MODEL_ROUTER exists in the enum so Java plans deserialize, but Python-side
    registration must fail loudly instead of dropping silently (see PR #964 review) —
    at every entry point that registers resources (they share one guard helper).
    """
    registrar = registrar_type()
    with pytest.raises(NotImplementedError, match="Java side"):
        registrar.add_resource("router", ResourceType.MODEL_ROUTER, object())
