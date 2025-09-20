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
import copy
import random
import time
from typing import Any

from pydantic import BaseModel

from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action, tool
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext


class ItemData(BaseModel):
    """Data model for storing item information.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    review : str
        The user review of the item
    review_score: float
        The review_score of the item
    """

    id: int
    review: str
    review_score: float
    memory_info: dict | None = None


class MyEvent(Event):  # noqa D101
    value: Any

class DataStreamAgent(Agent):
    """Agent used for explaining integrating agents with DataStream.

    Because pemja will find action in this class when execute Agent, we can't
    define this class directly in example.py for module name will be set
    to __main__.
    """

    @tool
    @staticmethod
    def my_tool(input: str) -> str:
        """Mark call tool.

        Parameters
        ----------
        input : str
            The input string

        Returns:
        -------
        str:
            The return string
        """
        return input + " call my tool"

    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        def log_to_stdout(input: Any, total: int) -> bool:
            # Simulating asynchronous time consumption
            time.sleep(random.random())
            print(f"[log_to_stdout] Logging input={input}, total reviews now={total}")
            return True

        input_data = event.input
        stm = ctx.short_term_memory

        current_total = stm.get("status.total_reviews") or 0
        total = current_total + 1
        stm.set("status.total_reviews", total)

        log_success = yield from ctx.execute_async(log_to_stdout, input_data, total)

        content = copy.deepcopy(input_data)
        content.review += " first action, log success=" + str(log_success) + ","
        content.memory_info = {"total_reviews": total}

        data_ref = stm.set(f"processed_items.item_{content.id}", content)
        ctx.send_event(MyEvent(value=data_ref.model_dump()))

    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext):  # noqa D102
        input_data = event.value
        stm = ctx.short_term_memory
        resolved_data: ItemData = stm.get(input_data)

        content = copy.deepcopy(resolved_data)
        content.review += " second action"
        tool = ctx.get_resource("my_tool", ResourceType.TOOL)
        content.review = tool.call(content.review)
        ctx.send_event(OutputEvent(output=content))


class TableAgent(Agent):
    """Agent used for explaining integrating agents with Table.

    Because pemja will find action in this class when execute Agent, we can't
    define this class directly in example.py for module name will be set
    to __main__.
    """

    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.input
        content = input
        content["review"] += " first action"
        ctx.send_event(MyEvent(value=content))

    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.value
        content = input
        content["review"] += " second action"
        ctx.send_event(OutputEvent(output=content))
