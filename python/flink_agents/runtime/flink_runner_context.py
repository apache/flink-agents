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
from typing import Any

import cloudpickle
from typing_extensions import override

from flink_agents.api.event import Event
from flink_agents.api.runner_context import RunnerContext


class FlinkRunnerContext(RunnerContext):
    """Providing context for agent execution in Flink Environment.

    This context allows access to event handling.
    """

    def __init__(self, j_runner_context: Any) -> None:
        """Initialize a flink runner context with the given java runner context.

        Parameters
        ----------
        j_runner_context : Any
            Java runner context used to synchronize data between Python and Java.
        """
        self._j_runner_context = j_runner_context

    @override
    def send_event(self, event: Event) -> None:
        """Send an event to the agent for processing.

        Parameters
        ----------
        event : Event
            The event to be processed by the agent system.
        """
        try:
            class_path = f"{event.__class__.__module__}.{event.__class__.__qualname__}"
            self._j_runner_context.sendEvent(class_path, cloudpickle.dumps(event))
        except Exception as e:
            err_msg = "Failed to send event " + class_path + " to runner context"
            raise RuntimeError(err_msg) from e

def create_flink_runner_context(j_runner_context: Any) -> FlinkRunnerContext:
    """Used to create a FlinkRunnerContext Python object in Pemja environment."""
    return FlinkRunnerContext(j_runner_context)
