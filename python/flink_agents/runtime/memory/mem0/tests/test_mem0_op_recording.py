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

import pytest

from flink_agents.api.memory.long_term_memory import MemorySet
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

    ltm.switch_context(
        "suppressed", observation_id="suppressed-action", observation_suppressed=True
    )
    ltm.add(ltm.get_memory_set("prefs"), "ignored")
    assert _drain(ltm, "suppressed", "suppressed-action") == []

    ltm.switch_context("observed", observation_id="observed-action")
    ltm.add(ltm.get_memory_set("prefs"), "recorded")
    assert [record["id"] for record in _drain(ltm, "observed", "observed-action")] == [
        "m1"
    ]
    assert ltm._update_observation_enabled is True
    assert ltm._get_observation_enabled is True
    assert ltm._search_observation_enabled is True


def test_empty_partition_key_is_refused_by_set_operations() -> None:
    mem0 = MagicMock()
    ltm = _make_ltm(mem0)
    empty_keyed = MemorySet(name="prefs", ltm=ltm, partition_key="")

    for op in (
        lambda: ltm.add(empty_keyed, "input"),
        lambda: ltm.get(empty_keyed),
        lambda: ltm.delete(empty_keyed),
        lambda: ltm.search(empty_keyed, "query", limit=5),
    ):
        with pytest.raises(ValueError, match="empty partition key"):
            op()

    mem0.add.assert_not_called()
    mem0.get_all.assert_not_called()
    mem0.delete_all.assert_not_called()
    mem0.search.assert_not_called()


def test_empty_partition_key_is_refused_by_memory_set_management() -> None:
    mem0 = MagicMock()
    ltm = _make_ltm(mem0)
    ltm.switch_context("", observation_id="empty-action")

    with pytest.raises(ValueError, match="empty partition key"):
        ltm.get_memory_set("prefs")
    with pytest.raises(ValueError, match="empty partition key"):
        ltm.delete_memory_set("prefs")

    mem0.delete_all.assert_not_called()


def test_memory_set_management_before_any_context_switch_is_refused() -> None:
    ltm = Mem0LongTermMemory.model_construct(
        ctx=MagicMock(), job_id="job", metric_group=None
    )

    with pytest.raises(ValueError, match="no partition key in scope"):
        ltm.get_memory_set("prefs")


def test_memory_set_stays_on_its_own_key_after_the_owner_switches() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {"results": []}
    mem0.get_all.return_value = {"results": []}
    mem0.search.return_value = {"results": []}
    ltm = _make_ltm(mem0)

    ltm.switch_context("owner", observation_id="owner-action")
    memory_set = ltm.get_memory_set("prefs")

    ltm.switch_context("other", observation_id="other-action")
    ltm.add(memory_set, "input")
    ltm.get(memory_set)
    ltm.search(memory_set, "query", limit=5)
    ltm.delete(memory_set)

    assert mem0.add.call_args.kwargs["agent_id"] == "owner"
    assert mem0.get_all.call_args.kwargs["agent_id"] == "owner"
    assert mem0.search.call_args.kwargs["agent_id"] == "owner"
    assert mem0.delete_all.call_args.kwargs["agent_id"] == "owner"


def test_observations_stay_with_the_action_that_obtained_the_set() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {
        "results": [{"event": "ADD", "id": "m1", "memory": "value"}]
    }
    mem0.get_all.return_value = {"results": [{"id": "m2", "memory": "stored"}]}
    mem0.search.return_value = {"results": []}
    ltm = _make_ltm(mem0)

    ltm.switch_context("owner", observation_id="owner-action")
    memory_set = ltm.get_memory_set("prefs")

    ltm.switch_context("other", observation_id="other-action")
    ltm.add(memory_set, "input")
    ltm.get(memory_set)
    ltm.search(memory_set, "query", limit=5)
    ltm.delete(memory_set)

    assert _drain(ltm, "other", "other-action") == []
    assert [record["op"] for record in _drain(ltm, "owner", "owner-action")] == [
        "ADD",
        "GET",
        "SEARCH",
        "DELETE_SET",
    ]


def test_unbound_memory_set_is_refused_rather_than_widened() -> None:
    mem0 = MagicMock()
    ltm = _make_ltm(mem0)
    unbound = MemorySet(name="prefs", ltm=ltm)

    for op in (
        lambda: ltm.add(unbound, "input"),
        lambda: ltm.get(unbound),
        lambda: ltm.delete(unbound),
        lambda: ltm.search(unbound, "query", limit=5),
    ):
        with pytest.raises(ValueError, match="not bound to a partition key"):
            op()

    mem0.add.assert_not_called()
    mem0.get_all.assert_not_called()
    mem0.delete_all.assert_not_called()
    mem0.search.assert_not_called()


def test_suppression_follows_the_set_not_the_current_context() -> None:
    mem0 = MagicMock()
    mem0.add.return_value = {
        "results": [{"event": "ADD", "id": "m1", "memory": "value"}]
    }
    ltm = _make_ltm(mem0)

    # Obtained while suppressed, used while the current context is not: the set's
    # own flag decides, so nothing is recorded.
    ltm.switch_context(
        "owner", observation_id="owner-action", observation_suppressed=True
    )
    suppressed_set = ltm.get_memory_set("prefs")
    ltm.switch_context("owner", observation_id="live-action")
    ltm.add(suppressed_set, "input")
    assert _drain(ltm, "owner", "owner-action") == []

    # And the reverse: obtained unsuppressed, used while the current context is
    # suppressed, so the operation is still recorded.
    unsuppressed_set = ltm.get_memory_set("prefs")
    ltm.switch_context(
        "owner", observation_id="quiet-action", observation_suppressed=True
    )
    ltm.add(unsuppressed_set, "input")
    assert [record["id"] for record in _drain(ltm, "owner", "live-action")] == ["m1"]
