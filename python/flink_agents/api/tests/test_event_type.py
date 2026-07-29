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
"""Smoke tests for :mod:`flink_agents.api.events.event_type`."""

from __future__ import annotations

from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent


def test_builtin_constants_match_event_class_constants() -> None:
    assert EventType.InputEvent == InputEvent.EVENT_TYPE
    assert EventType.OutputEvent == OutputEvent.EVENT_TYPE
    assert EventType.ChatRequestEvent == ChatRequestEvent.EVENT_TYPE
    assert EventType.ChatResponseEvent == ChatResponseEvent.EVENT_TYPE
    assert EventType.ToolRequestEvent == ToolRequestEvent.EVENT_TYPE
    assert EventType.ToolResponseEvent == ToolResponseEvent.EVENT_TYPE
    assert EventType.ContextRetrievalRequestEvent == ContextRetrievalRequestEvent.EVENT_TYPE
    assert EventType.ContextRetrievalResponseEvent == ContextRetrievalResponseEvent.EVENT_TYPE
