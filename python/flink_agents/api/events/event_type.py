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
"""Built-in event-type constants, sourced from each ``XxxEvent.EVENT_TYPE``."""

from __future__ import annotations

from flink_agents.api.events.chat_event import (
    ChatRequestEvent as _ChatRequestEvent,
)
from flink_agents.api.events.chat_event import (
    ChatResponseEvent as _ChatResponseEvent,
)
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent as _ContextRetrievalRequestEvent,
)
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalResponseEvent as _ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import (
    InputEvent as _InputEvent,
)
from flink_agents.api.events.event import (
    OutputEvent as _OutputEvent,
)
from flink_agents.api.events.memory_event import (
    LongTermGetEvent as _LongTermGetEvent,
)
from flink_agents.api.events.memory_event import (
    LongTermSearchEvent as _LongTermSearchEvent,
)
from flink_agents.api.events.memory_event import (
    LongTermUpdateEvent as _LongTermUpdateEvent,
)
from flink_agents.api.events.memory_event import (
    SensoryReadEvent as _SensoryReadEvent,
)
from flink_agents.api.events.memory_event import (
    SensoryWriteEvent as _SensoryWriteEvent,
)
from flink_agents.api.events.memory_event import (
    ShortTermReadEvent as _ShortTermReadEvent,
)
from flink_agents.api.events.memory_event import (
    ShortTermWriteEvent as _ShortTermWriteEvent,
)
from flink_agents.api.events.run_event import (
    AgentRunBeginEvent as _AgentRunBeginEvent,
)
from flink_agents.api.events.tool_event import (
    ToolRequestEvent as _ToolRequestEvent,
)
from flink_agents.api.events.tool_event import (
    ToolResponseEvent as _ToolResponseEvent,
)


class EventType:
    """Namespace of built-in event-type constants.

    Usage: ``@action(EventType.InputEvent)``.
    """

    InputEvent: str = _InputEvent.EVENT_TYPE
    OutputEvent: str = _OutputEvent.EVENT_TYPE
    ChatRequestEvent: str = _ChatRequestEvent.EVENT_TYPE
    ChatResponseEvent: str = _ChatResponseEvent.EVENT_TYPE
    ToolRequestEvent: str = _ToolRequestEvent.EVENT_TYPE
    ToolResponseEvent: str = _ToolResponseEvent.EVENT_TYPE
    ContextRetrievalRequestEvent: str = _ContextRetrievalRequestEvent.EVENT_TYPE
    ContextRetrievalResponseEvent: str = _ContextRetrievalResponseEvent.EVENT_TYPE
    ShortTermWriteEvent: str = _ShortTermWriteEvent.EVENT_TYPE
    ShortTermReadEvent: str = _ShortTermReadEvent.EVENT_TYPE
    SensoryWriteEvent: str = _SensoryWriteEvent.EVENT_TYPE
    SensoryReadEvent: str = _SensoryReadEvent.EVENT_TYPE
    LongTermUpdateEvent: str = _LongTermUpdateEvent.EVENT_TYPE
    LongTermGetEvent: str = _LongTermGetEvent.EVENT_TYPE
    LongTermSearchEvent: str = _LongTermSearchEvent.EVENT_TYPE
    AgentRunBeginEvent: str = _AgentRunBeginEvent.EVENT_TYPE
