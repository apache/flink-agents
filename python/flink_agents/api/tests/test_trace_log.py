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
from dataclasses import FrozenInstanceError

import pytest

from flink_agents.api.trace_log import TraceLogLevel, TraceLogTarget


@pytest.mark.parametrize(
    ("entity_type", "plural", "singular"),
    [
        ("event", TraceLogTarget.events, TraceLogTarget.event),
        ("action", TraceLogTarget.actions, TraceLogTarget.action),
        ("tool", TraceLogTarget.tools, TraceLogTarget.tool),
        ("llm", TraceLogTarget.llms, TraceLogTarget.llm),
        ("parser", TraceLogTarget.parsers, TraceLogTarget.parser),
        ("subagent", TraceLogTarget.subagents, TraceLogTarget.subagent),
    ],
)
def test_category_factories_match_generic_entities(entity_type, plural, singular):
    assert plural() == TraceLogTarget.entity(entity_type)
    assert plural(TraceLogLevel.OFF) == TraceLogTarget.entity(
        entity_type, level=TraceLogLevel.OFF
    )
    assert singular("com.example.*") == TraceLogTarget.entity(
        entity_type, "com.example.*"
    )
    assert singular("named", TraceLogLevel.VERBOSE) == TraceLogTarget.entity(
        entity_type, "named", TraceLogLevel.VERBOSE
    )
    with pytest.raises(ValueError, match="entityName must be a non-empty string"):
        singular(None)


def test_targets_are_immutable_and_distinguish_inherited_level():
    inherited = TraceLogTarget.all()
    explicit = TraceLogTarget.all(TraceLogLevel.STANDARD)
    assert inherited != explicit
    assert len({inherited, TraceLogTarget.all(), explicit}) == 2
    with pytest.raises(FrozenInstanceError):
        inherited._level = TraceLogLevel.OFF


@pytest.mark.parametrize("entity_type", [None, "", " ", 1])
def test_generic_factory_rejects_invalid_entity_type(entity_type):
    with pytest.raises(ValueError, match="entityType must be a non-empty string"):
        TraceLogTarget.entity(entity_type)


@pytest.mark.parametrize("name", ["", " ", "*", ".*", "foo*", "a.*.*", 1])
def test_generic_factory_validates_entity_name(name):
    with pytest.raises(ValueError, match="entityName"):
        TraceLogTarget.entity("custom", name)


def test_custom_types_and_names_remain_case_sensitive():
    assert TraceLogTarget.entity("embedding", "Search") != TraceLogTarget.entity(
        "embedding", "search"
    )
    assert TraceLogTarget.entity("Event") != TraceLogTarget.events()


def test_unknown_level_is_rejected():
    with pytest.raises(ValueError, match="Invalid TraceLogLevel"):
        TraceLogTarget.events("DEBUG")
