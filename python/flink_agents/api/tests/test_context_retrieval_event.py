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
"""Tests for the max_results validation of :class:`ContextRetrievalRequestEvent`."""

from __future__ import annotations

import pytest

from flink_agents.api.events.context_retrieval_event import ContextRetrievalRequestEvent


def test_zero_max_results_rejected() -> None:
    """A zero max_results should be rejected."""
    with pytest.raises(ValueError, match="max_results"):
        ContextRetrievalRequestEvent(query="flink", vector_store="store", max_results=0)


def test_negative_max_results_rejected() -> None:
    """A negative max_results should be rejected."""
    with pytest.raises(ValueError, match="max_results"):
        ContextRetrievalRequestEvent(
            query="flink", vector_store="store", max_results=-1
        )


def test_positive_max_results_accepted() -> None:
    """A positive max_results should be accepted."""
    event = ContextRetrievalRequestEvent(
        query="flink", vector_store="store", max_results=5
    )
    assert event.max_results == 5


def test_default_max_results_unchanged() -> None:
    """The default max_results should stay unchanged."""
    event = ContextRetrievalRequestEvent(query="flink", vector_store="store")
    assert event.max_results == 3
