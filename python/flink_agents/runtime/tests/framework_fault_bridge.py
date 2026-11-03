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
################################################################################
"""Provider-free fixtures for the real Pemja framework-fault contract."""

from typing import Any

from flink_agents.api.events.event import InputEvent
from flink_agents.plan.exceptions import UncapturableException


def raise_java(source):
    source.fail()


def wrap_java(source):
    try:
        source.fail()
    except Exception as error:
        message = "python wrapper"
        raise RuntimeError(message) from error


def ordinary_value():
    return False, None, "Type", "message", "trace"


class _YieldOnce:
    def __await__(self) -> Any:
        yield


async def async_java_fault(source):
    await _YieldOnce()
    source.fail()


def child_action(event, ctx):
    if InputEvent.from_event(event).input["framework"]:
        message = "Python child framework fault"
        raise UncapturableException(message) from OSError(
            "child state store unavailable"
        )
    message = "ordinary child rejected"
    raise ValueError(message)
