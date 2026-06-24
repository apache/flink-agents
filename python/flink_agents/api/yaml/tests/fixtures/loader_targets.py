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
"""Module-level callables referenced by YAML fixture files."""

from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def increment(event: Event, ctx: RunnerContext) -> None:
    value = InputEvent.from_event(event).input
    ctx.send_event(OutputEvent(output=value + 1))


def decrement(event: Event, ctx: RunnerContext) -> None:
    value = InputEvent.from_event(event).input
    ctx.send_event(OutputEvent(output=value - 1))


def notify(id: str, message: str) -> str:
    return f"notified {id}: {message}"


def query_order(order_id: str, tenant_id: str) -> str:
    return f"{tenant_id}:{order_id}"


class Counter:
    """Holder for a class-method action target — exercises the
    ``module:Class.method`` form in YAML function references.
    """

    @staticmethod
    def bump(event: Event, ctx: RunnerContext) -> None:
        value = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=value + 100))
