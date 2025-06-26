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

from flink_agents.api.event import Event, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def create_flink_runner_context(j_runner_context: Any):
    """Used to create a FlinkRunnerContext Python object in Pemja environment."""
    return FlinkRunnerContext(j_runner_context)


def convert_to_python_object(bytesObject: bytes):
    """Used for deserializing Python objects."""
    return cloudpickle.loads(bytesObject)


class FlinkRunnerContext(RunnerContext):
    """Providing context for workflow execution in Flink Environment.

    This context allows access to event handling.
    """

    def __init__(self, j_runner_context: Any):
        self._j_runner_context = j_runner_context

    @override
    def send_event(self, event: Event):
        """Send an event to the workflow for processing.
        Parameters
        ----------
        event : Event
            The event to be processed by the workflow system.
        """
        try:
            data = event.output if isinstance(event, OutputEvent) else event
            class_path = f"{event.__class__.__module__}.{event.__class__.__qualname__}"
            self._j_runner_context.sendEvent(class_path, cloudpickle.dumps(data))
        except Exception as e:
            raise RuntimeError(f"Failed to send event {event} to runner context: {e}")
