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
"""Prompt-driven example actions must emit valid empty user messages."""

from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import MessageRole
from flink_agents.api.events.event import InputEvent
from flink_agents.examples.quickstart.agents.product_suggestion_agent import (
    ProductSuggestionAgent,
)
from flink_agents.examples.quickstart.agents.review_analysis_agent import (
    ReviewAnalysisAgent,
)
from flink_agents.examples.quickstart.agents.table_review_analysis_agent import (
    TableReviewAnalysisAgent,
)


@pytest.mark.parametrize(
    ("agent", "payload"),
    [
        (ReviewAnalysisAgent, {"id": "product", "review": "damaged"}),
        (TableReviewAnalysisAgent, {"id": "product", "review": "damaged"}),
        (
            ProductSuggestionAgent,
            {"id": "product", "score_hist": ["1"], "unsatisfied_reasons": ["damaged"]},
        ),
    ],
)
def test_prompt_action_emits_valid_message(agent, payload):
    """The prompt supplies text; its placeholder must still satisfy ChatMessage."""
    context = MagicMock()
    agent.process_input(InputEvent(input=payload), context)
    request = context.send_event.call_args.args[0]
    assert len(request.messages) == 1
    assert request.messages[0].role == MessageRole.USER
    assert request.messages[0].blocks == ()
    assert "damaged" in request.prompt_args["input"]
