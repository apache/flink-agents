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
import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceType


def test_python_model_router_registration_is_an_explicit_error() -> None:
    """MODEL_ROUTER exists in the enum so Java plans deserialize, but Python-side
    registration must fail loudly instead of dropping silently (see PR #964 review).
    """
    agent = Agent()
    with pytest.raises(NotImplementedError, match="Java side"):
        agent.add_resource("router", ResourceType.MODEL_ROUTER, object())
