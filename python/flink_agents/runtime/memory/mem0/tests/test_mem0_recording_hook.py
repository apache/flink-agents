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
"""Tests for the private typed LTM observation buffer."""

import json
import threading
from typing import Any
from unittest.mock import MagicMock

from flink_agents.runtime.memory.mem0.mem0_long_term_memory import (
    Mem0LongTermMemory,
    _LtmObservationOp,
)


def _make_ltm() -> Mem0LongTermMemory:
    ctx = MagicMock()
    ctx.agent_metric_group = None
    ctx._j_runner_context = object()
    return Mem0LongTermMemory.model_construct(
        ctx=ctx, job_id="job", key="shared-partition", metric_group=None
    )


def test_records_are_versioned_and_search_keeps_set_and_query() -> None:
    ltm = _make_ltm()
    ltm._record_ltm_op(_LtmObservationOp.ADD, "prefs", "m1", "v", "partition")
    ltm._record_ltm_search("policies", "refund", [{"id": "p1"}], "partition")

    assert json.loads(ltm.drain_ltm_observation_records("partition")) == [
        {
            "op": "ADD",
            "set": "prefs",
            "id": "m1",
            "query": None,
            "value": "v",
            "version": 1,
        },
        {
            "op": "SEARCH",
            "set": "policies",
            "id": None,
            "query": "refund",
            "value": [{"id": "p1"}],
            "version": 1,
        },
    ]


def test_drain_rebuffers_other_partition_records() -> None:
    ltm = _make_ltm()
    ltm._record_ltm_op(_LtmObservationOp.ADD, "s", "a", "va", "partition-a")
    ltm._record_ltm_op(_LtmObservationOp.ADD, "s", "b", "vb", "partition-b")

    assert [
        record["id"]
        for record in json.loads(ltm.drain_ltm_observation_records("partition-a"))
    ] == ["a"]
    assert [
        record["id"]
        for record in json.loads(ltm.drain_ltm_observation_records("partition-b"))
    ] == ["b"]


def test_disabled_operation_does_not_enqueue_or_json_encode() -> None:
    ltm = _make_ltm()
    ltm._record_ltm_op(
        _LtmObservationOp.ADD,
        "s",
        "m1",
        object(),
        "partition",
        enabled=False,
    )
    assert ltm._ltm_observation_records.empty()


def test_buffer_is_thread_safe() -> None:
    ltm = _make_ltm()

    def worker() -> None:
        for index in range(100):
            ltm._record_ltm_op(
                _LtmObservationOp.ADD, "s", f"id-{index}", "v", "partition"
            )

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert len(json.loads(ltm.drain_ltm_observation_records("partition"))) == 200


class _RaisingQueue:
    def put(self, _item: Any) -> None:
        message = "boom"
        raise RuntimeError(message)


def test_buffering_hook_never_raises() -> None:
    ltm = _make_ltm()
    ltm._ltm_observation_records = _RaisingQueue()
    ltm._record_ltm_op(_LtmObservationOp.ADD, "s", "m1", "v", "partition")
