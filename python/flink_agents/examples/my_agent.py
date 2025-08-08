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
from typing import Any, Optional

from pydantic import BaseModel

from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
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
    memory_info: Optional[dict] = None


class MyEvent(Event):  # noqa D101
    value: Any


class DataStreamAgent(Agent):
    """Agent used for explaining integrating agents with DataStream.

    Because pemja will find action in this class when execute Agent, we can't
    define this class directly in example.py for module name will be set
    to __main__.
    """

    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        def log_to_stdout(input: Any, total: int) -> bool:
            # Simulating asynchronous time consumption
            time.sleep(random.random())
            print(f"[log_to_stdout] Logging input={input}, total reviews now={total}")
            return True

        input = event.input

        stm = ctx.get_short_term_memory()
        status = stm.new_object("status", overwrite=True)

        total = 0
        if stm.is_exist("status.total_reviews"):
            total = status.get("total_reviews")
        total += 1
        status.set("total_reviews", total)

        log_success = yield from ctx.execute_async(log_to_stdout, input, total)

        content = copy.deepcopy(input)
        content.review += " first action, log success=" + str(log_success) + ","
        ctx.send_event(MyEvent(value=content))

    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.value

        stm = ctx.get_short_term_memory()
        memory_info = {
            "total_reviews": stm.get("status.total_reviews"),
        }

        content = copy.deepcopy(input)
        content.review += " second action"
        content.memory_info = memory_info
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
