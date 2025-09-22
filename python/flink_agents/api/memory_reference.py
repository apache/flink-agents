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
from __future__ import annotations

from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, ConfigDict

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext


class MemoryRef(BaseModel):
    """Reference to a specific data item in the Short-Term Memory."""

    path: str

    model_config = ConfigDict(frozen=True)

    @staticmethod
    def create(path: str) -> MemoryRef:
        """Create a new MemoryRef instance based on the given path.

        Parameters
        ----------
        path: str
            The absolute path of the data in the Short-Term Memory.

        Returns:
        -------
        MemoryRef
            A new MemoryRef instance.
        """
        return MemoryRef(path=path)

    def resolve(self, ctx: RunnerContext) -> Any:
        """Resolve the reference to get the actual data.

        Parameters
        ----------
        ctx: RunnerContext
            The current execution context, used to access Short-Term Memory.

        Returns:
        -------
        Any
            The deserialized, original data object.
        """
        return ctx.short_term_memory.get(self)
