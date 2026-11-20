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
"""E2E test of context management through a Python Flink job.

The chat model, its tool and the policy are declared on ``ContextManagementAgent`` and
in the job configuration, so the test covers the configuration path, the tool-call
loop of the built-in actions, the summarizer call, the events an action can listen to
and their Trace Log records, with no live credentials.
"""

import json
import os
import sysconfig
from pathlib import Path
from typing import Dict, List

import pytest
from py4j.protocol import Py4JJavaError
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.context_management_event import ContextManagementEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.context_management_agent import (
    ContextManagementAgent,
    lookup_result,
)
from flink_agents.e2e_tests.e2e_tests_integration.internal_subagent_test import (
    InputKeySelector,
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

MANAGED = "sum 6 accounts"
MASKED = "masked sum 4 accounts"
UNMANAGED = "unmanaged sum 3 accounts"
CALLED = "called sum 4 accounts"


def _placeholder(account: int) -> str:
    return (
        f'[Tool result omitted by context management: lookup({{"account":{account}}})'
        f" returned {len(lookup_result(account))} characters]"
    )


def test_context_management_through_a_flink_job(tmp_path: Path) -> None:
    """One job, four requests.

    The job's policy cuts tool results to 1000 characters and, above 480 estimated
    tokens, summarizes every round but the last. The first request looks up six
    accounts under that policy. The second carries its own policy, which only masks
    the results of all rounds but the last. The third carries a disabled policy. The
    fourth is made with ``ctx.chat`` and the masking policy.
    """
    trace_dir = tmp_path / "trace_log"
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agent_config = agents.get_config()
    agent_config.set(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 1000
    )
    agent_config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 600)
    agent_config.set(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
    )
    agent_config.set_str("trace-log.base-dir", str(trace_dir))
    output = (
        agents.from_datastream(
            input=env.from_collection([MANAGED, MASKED, UNMANAGED, CALLED]),
            key_selector=InputKeySelector(),
        )
        .apply(ContextManagementAgent())
        .to_datastream()
    )
    result_dir = tmp_path / "results"
    output.map(str, Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            str(result_dir), Encoder.simple_string_encoder()
        ).build()
    )

    agents.execute()

    lines = sorted(
        line
        for path in result_dir.rglob("*")
        if path.is_file()
        for line in path.read_text().splitlines()
    )
    result_chars = len(lookup_result(1))
    masked_chars = sum(len(_placeholder(account)) for account in (1, 2, 3))
    assert lines == sorted(
        [
            # Every amount: each round was summarized before it left the model input,
            # and the model is sent one result at a time, cut to 1000 characters.
            f"{MANAGED} -> sum=210 accounts=6 results=1 result_chars=1000 "
            "steps=truncate" + ",summarize,truncate" * 5,
            # Masking alone hides the results of older rounds from the model, so the
            # scripted model only finds the amount of the last round.
            f"{MASKED} -> sum=40 accounts=1 results=4 "
            f"result_chars={masked_chars + result_chars} steps=mask,mask,mask",
            # Without a policy the model is sent every earlier result in full.
            f"{UNMANAGED} -> sum=60 accounts=3 results=3 "
            f"result_chars={3 * result_chars} steps=none",
            # The policy given to ctx.chat is applied to the call's tool rounds.
            f"{CALLED} -> sum=40 accounts=1 results=4 "
            f"result_chars={masked_chars + result_chars}",
        ]
    )

    # Every step is in the Trace Log, with what it did. The steps of the three
    # requests sent as events are records of their own.
    steps = _steps_by_request(trace_dir, ContextManagementEvent.EVENT_TYPE)
    assert sorted(len(events) for events in steps.values()) == [3, 11]
    managed = next(events for events in steps.values() if len(events) == 11)
    assert [event["step"] for event in managed] == (
        ["truncate"] + ["summarize", "truncate"] * 5
    )
    assert managed[0]["details"] == {
        "max_chars": 1000,
        "results": [
            {
                "call_id": "call-1",
                "tool": "lookup",
                "original_chars": result_chars,
                "sent_chars": 1000,
            }
        ],
    }
    summaries = [event["details"] for event in managed if event["step"] == "summarize"]
    assert [details["summarized_rounds"] for details in summaries] == [1, 2, 3, 4, 5]
    for details in summaries:
        assert details["summarizer"] == "chat"
        assert details["rounds"] == 1
        assert details["max_input_tokens"] == 600
        assert details["estimate_approximate"] is True
        assert details["estimated_input_tokens_before"] > 480
        assert details["estimated_input_tokens_after"] < 480
        assert "error" not in details
    # The steps of the ctx.chat call stay inside the call, like its other events: the
    # Trace Log records them in the call's envelope.
    called = _steps_by_request(trace_dir, "_internal_subgraph")
    assert len(called) == 1
    masked = [
        next(events for events in steps.values() if len(events) == 3),
        next(iter(called.values())),
    ]
    for events in masked:
        assert [event["step"] for event in events] == ["mask"] * 3
        assert [event["details"]["results"][0]["call_id"] for event in events] == [
            "call-1",
            "call-2",
            "call-3",
        ]
        assert all(
            event["details"]["results"][0]["omitted_chars"] == result_chars
            for event in events
        )


def test_an_invalid_option_fails_when_the_job_is_built() -> None:
    """The options are checked before the job runs, also for a Python agent."""
    env = StreamExecutionEnvironment.get_execution_environment()
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5
    )
    builder = agents.from_datastream(
        input=env.from_collection([MANAGED]), key_selector=InputKeySelector()
    ).apply(ContextManagementAgent())

    with pytest.raises(
        Py4JJavaError,
        match="context-management.tool-results.max-chars must be at least 100, got 5",
    ):
        builder.to_datastream()


def _steps_by_request(trace_dir: Path, entity_name: str) -> Dict[str, List[dict]]:
    """Return the context management events in the Trace Log, by request id.

    ``entity_name`` selects the records to read: the event type itself for requests
    sent as events, or the envelope of the events inside a ``ctx.chat`` call.
    """
    steps: Dict[str, List[dict]] = {}
    for log_file in trace_dir.glob("traces-*.log"):
        with log_file.open(encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                record = json.loads(line)
                if (
                    record.get("entityType") != "event"
                    or record.get("entityName") != entity_name
                ):
                    continue
                attributes = record["attributes"]
                if entity_name != ContextManagementEvent.EVENT_TYPE:
                    if attributes["delegate_type"] != ContextManagementEvent.EVENT_TYPE:
                        continue
                    attributes = attributes["delegate_attributes"]
                steps.setdefault(attributes["request_id"], []).append(attributes)
    return steps
