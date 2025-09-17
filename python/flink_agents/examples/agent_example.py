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
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel

from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.memory_reference import MemoryRef


class ProcessedData(BaseModel):  # noqa D101
    content: str
    visit_count: int

class MyEvent(Event):  # noqa D101
    value: Any


# TODO: Replace this agent with more practical example.
class MyAgent(Agent):
    """An example of agent to show the basic usage.

    Currently, this agent doesn't really make sense, and it's mainly for developing
    validation.
    """

    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        key = ctx.key
        input_message = event.input
        memory = ctx.short_term_memory

        data_path = f"user_data.{key}"
        previous_data: ProcessedData = memory.get(data_path)
        current_count = previous_data.visit_count if previous_data else 0
        new_count = current_count + 1

        data_to_store = ProcessedData(content=input_message,visit_count=new_count)
        data_ref = memory.set(data_path, data_to_store)

        ctx.send_event(MyEvent(value=data_ref))

        processed_content = f"{input_message} -> processed_by_first_action"
        key_with_count = f"(visit {new_count} times)"
        ctx.send_event(OutputEvent(output={key_with_count:processed_content}))


    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext):  # noqa D102
        content_ref: MemoryRef = event.value
        memory = ctx.short_term_memory

        processed_data: ProcessedData = memory.get(content_ref)

        base_message = processed_data.content
        current_count = processed_data.visit_count
        new_count = current_count + 1

        updated_data_to_store = ProcessedData(content=base_message, visit_count=new_count)
        memory.set(content_ref.path, updated_data_to_store)

        final_content = f"{base_message} -> processed by second_action"
        key_with_count = f"(visit {new_count} times)"
        ctx.send_event(OutputEvent(output={key_with_count: final_content}))


if __name__ == "__main__":
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = MyAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": "The message from bob"})
    input_list.append({"k": "john", "v": "The message from john"})
    input_list.append({"key": "john", "value": "Second message from john"})
    input_list.append({"key": "bob", "value": "Second message from bob"})
    input_list.append(
        {"value": "Message from unknown"}
    )  # will automatically generate a new unique key

    env.execute()

    for output in output_list:
        print(output)
