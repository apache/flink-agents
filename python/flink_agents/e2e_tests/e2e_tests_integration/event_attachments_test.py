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
import os
import sys
import sysconfig
from pathlib import Path
from typing import Any

from pyflink.common import Configuration
from pyflink.datastream import KeySelector, StreamExecutionEnvironment

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

current_dir = Path(__file__).parent
os.environ["PYTHONPATH"] = (
    f"{current_dir.parent.parent.parent}:{sysconfig.get_paths()['purelib']}"
)


class _KeySelector(KeySelector):
    def get_key(self, value: dict[str, Any]) -> str:
        return str(value["key"])


class EventAttachmentsAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def send_attachment(event: Event, ctx: RunnerContext) -> None:
        value = InputEvent.from_event(event).input
        ctx.send_event(
            Event(
                type="AttachmentStep",
                attributes={"kind": "inline"},
                attachments={
                    "payload": {
                        "value": value,
                        "items": [1, 2, 3],
                    }
                },
            )
        )

    @action("AttachmentStep")
    @staticmethod
    def receive_attachment(event: Event, ctx: RunnerContext) -> None:
        attachment = event.get_attachment("payload")
        ctx.send_event(
            OutputEvent(
                output={
                    "kind": event.get_attr("kind"),
                    "payload": {
                        "value": attachment["value"],
                        "items": attachment["items"],
                    },
                }
            )
        )


def test_python_event_attachments_roundtrip_on_flink() -> None:
    config = Configuration()
    config.set_string("python.pythonpath", os.environ["PYTHONPATH"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_python_executable(sys.executable)
    env.set_parallelism(1)
    input_stream = env.from_collection(
        [{"key": "k1", "value": {"message": "hello"}}]
    )
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output = (
        agents_env.from_datastream(input_stream, _KeySelector())
        .apply(EventAttachmentsAgent())
        .to_datastream()
    )

    assert list(output.execute_and_collect()) == [
        {
            "kind": "inline",
            "payload": {
                "value": {"key": "k1", "value": {"message": "hello"}},
                "items": [1, 2, 3],
            },
        }
    ]
