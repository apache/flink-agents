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
"""Public Mem0 operations produce correctly typed observations."""

import json
from concurrent.futures import ThreadPoolExecutor
from inspect import signature
from threading import Event
from typing import Any
from unittest.mock import MagicMock

from flink_agents.runtime.memory.internal_base_long_term_memory import (
    InternalBaseLongTermMemory,
)
from flink_agents.runtime.memory.mem0.mem0_long_term_memory import Mem0LongTermMemory


def _make_ltm(mem0: Any) -> Mem0LongTermMemory:
    ctx = MagicMock()
    ctx.agent_metric_group = None
    ltm = Mem0LongTermMemory.model_construct(
        ctx=ctx, job_id="job", key="partition", metric_group=None
    )
    ltm._mem0 = mem0
    ltm._observation_id = "action"
    ltm.configure_observation(
        update_observation_enabled=True,
        get_observation_enabled=True,
        search_observation_enabled=True,
    )
    return ltm


def _drain(
    ltm: Mem0LongTermMemory,
    key: str = "partition",
    observation_id: str = "action",
) -> list[dict]:
    return json.loads(ltm.drain_ltm_observation_records(key, observation_id))


def test_internal_context_signatures_match_implementation() -> None:
    assert signature(
        InternalBaseLongTermMemory.configure_observation, eval_str=True
    ) == signature(
        Mem0LongTermMemory.configure_observation,
        eval_str=True,
    )
    assert signature(
        InternalBaseLongTermMemory.switch_context, eval_str=True
    ) == signature(
        Mem0LongTermMemory.switch_context,
        eval_str=True,
    )


def test_add_maps_mem0_add_update_delete_results() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {
        "results": [
            {"event": "ADD", "id": "a", "memory": "added"},
            {"event": "UPDATE", "id": "u", "memory": "updated"},
            {"event": "DELETE", "id": "d", "memory": "ignored"},
        ]
    }
    ltm = _make_ltm(mem0)

    assert ltm.add(ltm.get_memory_set("prefs"), "input") == ["a", "u", "d"]
    assert [
        (record["op"], record["id"], record["value"]) for record in _drain(ltm)
    ] == [
        ("ADD", "a", "added"),
        ("UPDATE", "u", "updated"),
        ("DELETE", "d", None),
    ]


def test_unknown_mem0_result_is_not_mislabeled_as_add() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {
        "results": [{"event": "NOOP", "id": "x", "memory": "value"}]
    }
    ltm = _make_ltm(mem0)
    assert ltm.add(ltm.get_memory_set("prefs"), "input") == ["x"]
    assert _drain(ltm) == []


def test_public_method_captures_observation_owner_at_entry() -> None:
    mem0 = MagicMock()
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("prefs")

    def finish_after_context_switch(**_kwargs: Any) -> dict:
        ltm.key = "partition-2"
        ltm._observation_id = "action-2"
        return {"results": [{"event": "ADD", "id": "m1", "memory": "v"}]}

    mem0.add.side_effect = finish_after_context_switch
    ltm.add(memory_set, "input")

    assert [record["id"] for record in _drain(ltm)] == ["m1"]
    assert _drain(ltm, "partition-2", "action-2") == []


def test_add_uses_entry_context_when_another_action_switches_context() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {"results": []}
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("prefs")
    observation_config_read = Event()
    resume_operation = Event()

    class BlockingEnabledFlag:
        def __bool__(self) -> bool:
            observation_config_read.set()
            if not resume_operation.wait(timeout=5):
                message = "Timed out waiting for the context switch"
                raise TimeoutError(message)
            return True

    ltm._update_observation_enabled = BlockingEnabledFlag()
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(ltm.add, memory_set, "input")
        assert observation_config_read.wait(timeout=5)
        ltm.switch_context("partition-2", observation_id="action-2")
        resume_operation.set()
        future.result(timeout=5)

    assert mem0.add.call_args.kwargs["agent_id"] == "partition"


def test_get_delete_and_search_keep_structured_identity() -> None:
    mem0 = MagicMock()
    mem0.get.return_value = {"id": "g1", "memory": "value"}
    mem0.search.return_value = {
        "results": [{"id": "s1", "memory": "hit", "score": 0.9}]
    }
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("policies")

    ltm.get(memory_set, ids="g1")
    ltm.delete(memory_set, ids="d1")
    ltm.search(memory_set, "refund", limit=5)

    records = _drain(ltm)
    assert [(record["op"], record["set"]) for record in records] == [
        ("GET", "policies"),
        ("DELETE", "policies"),
        ("SEARCH", "policies"),
    ]
    assert records[2]["query"] == "refund"


def test_context_switch_changes_observation_owner_and_current_suppression() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {
        "results": [{"event": "ADD", "id": "m1", "memory": "value"}]
    }
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("prefs")

    ltm.switch_context(
        "suppressed", observation_id="suppressed-action", observation_suppressed=True
    )
    ltm.add(memory_set, "ignored")
    assert _drain(ltm, "suppressed", "suppressed-action") == []

    ltm.switch_context("observed", observation_id="observed-action")
    ltm.add(memory_set, "recorded")
    assert [record["id"] for record in _drain(ltm, "observed", "observed-action")] == [
        "m1"
    ]
    assert ltm._update_observation_enabled is True
    assert ltm._get_observation_enabled is True
    assert ltm._search_observation_enabled is True


def test_empty_context_key_is_used_consistently_for_mem0_operations() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {"results": []}
    mem0.get_all.return_value = {"results": []}
    mem0.search.return_value = {"results": []}
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("prefs")

    ltm.switch_context("", observation_id="empty-action")
    ltm.add(memory_set, "input")
    ltm.get(memory_set)
    ltm.search(memory_set, "query", limit=5)
    ltm.delete(memory_set)
    ltm.delete_memory_set("prefs")

    assert mem0.add.call_args.kwargs["agent_id"] == ""
    assert mem0.get_all.call_args.kwargs["agent_id"] == ""
    assert mem0.search.call_args.kwargs["agent_id"] == ""
    assert [call.kwargs["agent_id"] for call in mem0.delete_all.call_args_list] == [
        "",
        "",
    ]
