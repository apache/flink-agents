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

from flink_agents.api.decorators import action
from flink_agents.api.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.workflow import Workflow


class MyEvent(Event):  # noqa D101
    value: Any


class MyWorkflow(Workflow):
    """Workflow used for explaining integrating agents with DataStream.

    Because pemja will find action in this class when execute workflow, we can't
    define this class directly in stream_agent_example.py for module name will be set
    to __main__.
    """

    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.input
        content = input.get_review() + " first action."
        ctx.send_event(MyEvent(value=content))

    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.value
        content = input + " second action."
        ctx.send_event(OutputEvent(output=content))
