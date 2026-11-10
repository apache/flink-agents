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
from flink_agents.api.chat_models.routing import ModelRouter, Strategies
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
def test_python_model_router_registration(registrar_type: type) -> None:
    registrar = registrar_type()
    descriptor = ModelRouter.of("small").strategy(Strategies.rules({})).build()
    registrar.add_resource("router", ResourceType.MODEL_ROUTER, descriptor)
    assert registrar.resources[ResourceType.MODEL_ROUTER]["router"] == descriptor


@pytest.mark.parametrize("registrar_type", [Agent, _StubExecutionEnvironment])
@pytest.mark.parametrize(
    ("first", "second"),
    [
        (ResourceType.CHAT_MODEL, ResourceType.MODEL_ROUTER),
        (ResourceType.MODEL_ROUTER, ResourceType.CHAT_MODEL),
    ],
)
def test_chat_model_and_router_names_collide(registrar_type, first, second):
    registrar = registrar_type()
    registrar.add_resource("same", first, object())
    with pytest.raises(ValueError, match="both a chat model and a model router"):
        registrar.add_resource("same", second, object())
