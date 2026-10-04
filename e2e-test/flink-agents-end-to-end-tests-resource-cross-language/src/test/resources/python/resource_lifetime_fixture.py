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
################################################################################

from typing import Any, Dict

from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.resource import ResourceType


class LifetimeChatModelSetup(BaseChatModelSetup):
    """Minimal resource used to exercise Pemja handle lifetime."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    def open(self) -> None:
        self.resource_context.get_resource(
            "lifetimeDependency", ResourceType.CHAT_MODEL
        )

    def touch(self) -> str:
        return "alive"

    def close(self) -> None:
        pass


class LifetimeDependencySetup(BaseChatModelSetup):
    """Nested Python resource created while the outer resource is opening."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    def open(self) -> None:
        pass

    def close(self) -> None:
        pass
