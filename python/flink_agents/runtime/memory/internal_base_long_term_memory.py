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
from abc import ABC, abstractmethod

from flink_agents.api.memory.long_term_memory import BaseLongTermMemory


class InternalBaseLongTermMemory(BaseLongTermMemory, ABC):
    """Internal interface extends BaseLongTermMemory for hiding some interface
    to user.
    """

    @abstractmethod
    def configure_observation(
        self,
        *,
        update_observation_enabled: bool,
        get_observation_enabled: bool,
        search_observation_enabled: bool,
    ) -> None:
        """Configure which operations produce observation records."""

    @abstractmethod
    def switch_context(self, key: str, *, observation_suppressed: bool = False) -> None:
        """Switches the context for the memory operations. This allows
        the same memory instance to be used for different key by isolating
        data based on the provided key.

        Args:
            key: The context key.
            observation_suppressed: Whether to suppress observation for the action.
        """
