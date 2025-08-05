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
from typing import Type

import pytest
from pydantic import ValidationError
from pydantic_core import PydanticSerializationError
from pyflink.common import Row

from flink_agents.api.events.event import Event, InputEvent, OutputEvent


def test_event_init_serializable() -> None:  # noqa D103
    Event(a=1, b=InputEvent(input=1), c=OutputEvent(output="111"))


def test_event_init_non_serializable() -> None:  # noqa D103
    with pytest.raises(ValidationError):
        Event(a=1, b=Type[InputEvent])


def test_event_setattr_serializable() -> None:  # noqa D103
    event = Event(a=1)
    event.c = Event()


def test_event_setattr_non_serializable() -> None:  # noqa D103
    event = Event(a=1)
    with pytest.raises(PydanticSerializationError):
        event.c = Type[InputEvent]


def test_input_event_ignore_row_unserializable() -> None:  # noqa D103
    InputEvent(input=Row({"a": 1}))
