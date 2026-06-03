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
"""Python smoke tests for :mod:`flink_agents.api.events.event_type`.

The authoritative test suite lives in Java
(``api/src/test/java/.../EventTypeTest.java``) per cross-language test policy
(issue #006, Q5). These two cases only verify the Python language-binding
layer; full semantic coverage belongs on the Java side.
"""

from __future__ import annotations

from typing import ClassVar

import pytest

from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.events.event_type import (
    EventType,
    _clear_user_registered_for_testing,
    lookup_or_self,
    register,
)


@pytest.fixture(autouse=True)
def _reset_user_registry() -> None:
    _clear_user_registered_for_testing()
    yield
    _clear_user_registered_for_testing()


def test_builtin_lookup_and_constant_alignment() -> None:
    """Smoke: built-in EventType constants resolve and match XxxEvent.EVENT_TYPE."""
    assert EventType.InputEvent == InputEvent.EVENT_TYPE
    assert lookup_or_self("InputEvent") == InputEvent.EVENT_TYPE
    # Unknown short name passes through unchanged.
    assert lookup_or_self("NotRegistered") == "NotRegistered"


def test_register_user_event_then_lookup_resolves_short_name() -> None:
    """Smoke: register a user event and lookup the short name."""

    class MyOrderEvent(Event):
        EVENT_TYPE: ClassVar[str] = "_my_order_event"

        def __init__(self) -> None:
            super().__init__(type=MyOrderEvent.EVENT_TYPE)

    register(MyOrderEvent)
    assert lookup_or_self("MyOrderEvent") == "_my_order_event"
