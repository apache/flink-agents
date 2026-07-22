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
from typing import Any
from unittest.mock import MagicMock

from flink_agents.runtime.memory.mem0.mem0_long_term_memory import Mem0LongTermMemory


def _make_ltm(mem0: Any) -> Mem0LongTermMemory:
    ctx = MagicMock()
    ctx.agent_metric_group = None
    ctx._j_runner_context = object()
    ltm = Mem0LongTermMemory.model_construct(
        ctx=ctx, job_id="job", key="partition", metric_group=None
    )
    ltm._mem0 = mem0
    ltm._update_observation_enabled = True
    ltm._get_observation_enabled = True
    ltm._search_observation_enabled = True
    return ltm


def _drain(ltm: Mem0LongTermMemory, key: str = "partition") -> list[dict]:
    return json.loads(ltm.drain_ltm_observation_records(key))


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


def test_public_method_captures_partition_key_at_entry() -> None:
    mem0 = MagicMock()
    ltm = _make_ltm(mem0)
    memory_set = ltm.get_memory_set("prefs")

    def finish_after_context_switch(**_kwargs: Any) -> dict:
        ltm.key = "partition-2"
        return {"results": [{"event": "ADD", "id": "m1", "memory": "v"}]}

    mem0.add.side_effect = finish_after_context_switch
    ltm.add(memory_set, "input")

    assert [record["id"] for record in _drain(ltm)] == ["m1"]
    assert _drain(ltm, "partition-2") == []


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
