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
import json
import os
import subprocess
import sys
import sysconfig
import time
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pyflink.common import Configuration, WatermarkStrategy
from pyflink.datastream import (
    KeySelector,
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
)
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.core_options import AgentConfigOptions, AgentExecutionOptions
from flink_agents.api.decorators import action, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.trace import ExecutionEntityTypes, ExecutionReporters
from flink_agents.api.trace_log import TraceLogTarget

# Keep the active source checkout visible to the embedded Python interpreter.
os.environ["PYTHONPATH"] = os.pathsep.join(
    filter(None, [os.environ.get("PYTHONPATH"), sysconfig.get_paths()["purelib"]])
)


class InputKeySelector(KeySelector):
    """Key selector for input data."""

    def get_key(self, value: dict) -> int:
        """Extract key from input data."""
        return value.get("id", 0)


class PythonTraceLoggingAgent(Agent):
    """Agent for testing Python trace logging."""

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send an output event."""
        input_data = InputEvent.from_event(event).input
        ctx.send_event(
            OutputEvent(output={"processed_review": f"{input_data['review']}"})
        )


class PythonExecutionReportingAgent(Agent):
    """Report overlapping component calls before emitting the action's output."""

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Retain the identities returned when creating both calls."""
        metadata = {"request": "shared"}
        target = (ctx, ExecutionEntityTypes.TOOL, "shared_tool")
        success_id = ExecutionReporters.created(*target, metadata)
        failure_id = ExecutionReporters.created(*target, metadata)
        ExecutionReporters.started(*target, metadata, execution_id=success_id)
        ExecutionReporters.started_at(
            *target,
            metadata,
            datetime.now(timezone.utc).isoformat(),
            execution_id=failure_id,
        )

        first = (ctx, success_id, ExecutionEntityTypes.TOOL, "shared_tool")
        second = (ctx, failure_id, ExecutionEntityTypes.TOOL, "shared_tool")
        completed_metadata = {
            **metadata,
            "completed": True,
            "callerExecutionIds": {"success": success_id, "failed": failure_id},
        }
        failure = ValueError("reported component failure")
        ExecutionReporters.succeeded_at(
            *first, completed_metadata, datetime.now(timezone.utc).isoformat()
        )
        ExecutionReporters.failed_at(
            *second,
            completed_metadata,
            failure,
            "test_call_failed",
            datetime.now(timezone.utc).isoformat(),
        )
        ExecutionReporters.succeeded(*first, completed_metadata)
        ExecutionReporters.failed(
            *second, completed_metadata, failure, "test_call_failed"
        )
        PythonTraceLoggingAgent.process_input(event, ctx)


def _wait_for_trace_marker(marker: str) -> str:
    """Wait for a sibling Action without accessing its runner context."""
    deadline = time.monotonic() + 30
    while not Path(marker).exists():
        if time.monotonic() >= deadline:
            msg = f"Sibling Action did not create {marker}"
            raise TimeoutError(msg)
        time.sleep(0.01)
    return "released"


class PythonAwaitExecutionReportingAgent(Agent):
    """Switch Action contexts while a component call awaits its result."""

    @action(EventType.InputEvent)
    @staticmethod
    async def await_result(event: Event, ctx: RunnerContext) -> None:
        """Keep the call's identity while the sibling Action releases its wait."""
        gate_dir = Path(InputEvent.from_event(event).input["gate_dir"])
        execution_id = ExecutionReporters.started(
            ctx, ExecutionEntityTypes.TOOL, "shared_tool"
        )
        (gate_dir / "started").touch()
        result = await ctx.durable_execute_async(
            _wait_for_trace_marker, str(gate_dir / "release")
        )
        ExecutionReporters.succeeded(
            ctx, execution_id, ExecutionEntityTypes.TOOL, "shared_tool"
        )
        ctx.send_event(
            OutputEvent(output={"executionId": execution_id, "result": result})
        )

    @action(EventType.InputEvent)
    @staticmethod
    async def release_result(event: Event, ctx: RunnerContext) -> None:
        """Report under another Action context before allowing the caller to resume."""
        gate_dir = Path(InputEvent.from_event(event).input["gate_dir"])
        # Both Actions are already queued for the same input. This handshake works
        # whichever Action runs first, and the gate cannot open before the caller yields.
        await ctx.durable_execute_async(
            _wait_for_trace_marker, str(gate_dir / "started")
        )
        execution_id = ExecutionReporters.started(
            ctx, ExecutionEntityTypes.TOOL, "shared_tool"
        )
        ExecutionReporters.succeeded(
            ctx, execution_id, ExecutionEntityTypes.TOOL, "shared_tool"
        )
        (gate_dir / "release").touch()


class TraceUsageChatModel(BaseChatModelSetup):
    """Return scripted token counters without an external service."""

    @override
    def open(self) -> None:
        """No connection is needed by the offline fixture."""

    @property
    @override
    def model_kwargs(self) -> dict[str, Any]:
        """Return no provider arguments."""
        return {}

    @override
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
        """Read the scripted response metadata from the request."""
        metadata = json.loads(messages[-1].text)
        return ChatResult(
            message=ChatMessage.assistant("reply"),
            model=metadata.get("model_name"),
            usage=TokenUsage(
                prompt_tokens=metadata.get("promptTokens"),
                completion_tokens=metadata.get("completionTokens"),
            ),
        )


class PythonLlmTraceUsageAgent(Agent):
    """Exercise the built-in ChatModelAction with an offline model."""

    @chat_model_setup
    @staticmethod
    def offline_model() -> ResourceDescriptor:
        """Configure the model independently of the response's token counters."""
        return ResourceDescriptor(
            clazz=f"{TraceUsageChatModel.__module__}.{TraceUsageChatModel.__name__}",
            connection="unused",
            model="request-alias",
        )

    @action(EventType.InputEvent)
    @staticmethod
    def request_chat(event: Event, ctx: RunnerContext) -> None:
        """Send a request through the built-in chat action."""
        metadata = InputEvent.from_event(event).input["response_metadata"]
        ctx.send_event(
            ChatRequestEvent(
                model="offline_model",
                messages=[ChatMessage.user(json.dumps(metadata))],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def emit_response(event: Event, ctx: RunnerContext) -> None:
        """Expose metadata after the response Event returns through Java."""
        response = ChatResponseEvent.from_event(event).response
        ctx.send_event(
            OutputEvent(output={"response": response.model_dump(mode="json")})
        )


def test_python_trace_logging(tmp_path: Path) -> None:
    """Verify the Python runtime writes Event payloads in native TraceRecords."""
    records = _read_log_records(_run_trace_logging_pipeline(tmp_path))
    assert records, "Trace log file is empty."
    assert all(record["entityType"] == "event" for record in records)
    outputs = [
        record for record in records if record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    assert outputs, "Trace log should contain Python output Events."
    for record in outputs:
        assert record["timestamp"]
        assert record["level"] == "STANDARD"
        assert record["entityMetadata"]["eventId"]
        assert "processed_review" in record["attributes"]["output"]


def _run_trace_logging_pipeline(
    tmp_path: Path,
    targets: list[TraceLogTarget] | None = None,
    agent: Agent | None = None,
    input_path: Path | None = None,
) -> Path:
    """Run the trace logging pipeline and return the trace log directory.

    Args:
        tmp_path: Temporary directory for log output.
        targets: Optional trace log recording targets.
        agent: Optional agent to execute for each input.
        input_path: Optional JSON-lines input file.

    Returns:
        The trace log directory path.
    """
    trace_log_dir = tmp_path / "trace_log"

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set_str("trace-log.base-dir", str(trace_log_dir))
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)

    if targets is not None:
        agents_env.get_config().set(AgentConfigOptions.TRACE_LOG_TARGETS, targets)

    if input_path is None:
        input_path = Path(__file__).parent / "../resources/input/input_data.txt"
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            input_path.resolve().as_uri(),
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="python_trace_logging_test",
    )

    deserialize_datastream = input_datastream.map(lambda x: json.loads(x))

    agents_env.from_datastream(
        input=deserialize_datastream, key_selector=InputKeySelector()
    ).apply(agent if agent is not None else PythonTraceLoggingAgent()).to_datastream()

    agents_env.execute()
    return trace_log_dir


def _read_log_records(trace_log_dir: Path) -> list[dict]:
    """Read all JSON records from trace log files.

    Args:
        trace_log_dir: Directory containing trace log files.

    Returns:
        List of parsed JSON records.
    """
    records: list[dict] = []
    for log_file in trace_log_dir.glob("traces-*.log"):
        with log_file.open(encoding="utf-8") as handle:
            records.extend(json.loads(line) for line in handle if line.strip())
    assert records
    assert all(
        type(record.get("schemaVersion")) is int and record["schemaVersion"] == 1
        for record in records
    )
    return records


def test_trace_tree_reads_runtime_event_lineage(tmp_path: Path) -> None:
    """The reader reconstructs the Event flow written by a PyFlink job."""
    trace_log_dir = _run_trace_logging_pipeline(
        tmp_path, targets=[TraceLogTarget.all()]
    )
    records = _read_log_records(trace_log_dir)
    input_events = {
        record["entityMetadata"]["eventId"]: record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == InputEvent.EVENT_TYPE
    }
    output_events = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    actions = {
        record["executionId"]: record
        for record in records
        if record["entityType"] == "action"
        and record["entityName"] == "process_input"
        and record["status"] == "started"
    }

    assert input_events
    assert len(output_events) == len(input_events)
    assert len(actions) == len(input_events)

    reader_result = subprocess.run(
        [
            sys.executable,
            "-m",
            "flink_agents.cli.trace_tree",
            str(trace_log_dir),
            "--format",
            "json",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    trace_forest = json.loads(reader_result.stdout)
    assert set(trace_forest["roots"]) == set(input_events)
    assert trace_forest["warnings"] == []
    assert reader_result.stderr == ""
    for input_event in input_events.values():
        metadata = input_event["entityMetadata"]
        assert "producerExecutionId" not in metadata
        assert "upstreamEventId" not in metadata
        assert "upstreamActionName" not in metadata

    for output_event in output_events:
        metadata = output_event["entityMetadata"]
        input_event = input_events[metadata["upstreamEventId"]]
        action_record = actions[metadata["producerExecutionId"]]
        assert metadata["upstreamActionName"] == "process_input"
        assert action_record["entityName"] == "process_input"
        assert (
            action_record["entityMetadata"]["triggerEventId"]
            == metadata["upstreamEventId"]
        )
        assert output_event["inputRunId"] == input_event["inputRunId"]
        assert action_record["inputRunId"] == input_event["inputRunId"]
        assert "parentExecutionId" not in action_record

        upstream_node = trace_forest["nodes"][metadata["upstreamEventId"]]
        process_action = next(
            action
            for action in upstream_node["actions"]
            if action["name"] == "process_input"
        )
        assert metadata["eventId"] in process_action["children"]


def test_python_execution_reports_preserve_caller_identity(tmp_path: Path) -> None:
    """Component reports retain caller identities through the Java trace logger."""
    records = _read_log_records(
        _run_trace_logging_pipeline(
            tmp_path,
            targets=[TraceLogTarget.all()],
            agent=PythonExecutionReportingAgent(),
        )
    )
    inputs = {
        record["entityMetadata"]["eventId"]: record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == InputEvent.EVENT_TYPE
    }
    actions = {
        record["executionId"]: record
        for record in records
        if record["entityType"] == "action"
        and record["entityName"] == "process_input"
        and record["status"] == "started"
    }
    outputs = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    components = [
        record
        for record in records
        if record["entityType"] == ExecutionEntityTypes.TOOL
    ]
    assert inputs
    assert len(actions) == len(inputs) == len(outputs)
    assert len(components) == 8 * len(inputs)
    assert len({record["executionId"] for record in components}) == 2 * len(inputs)

    for action_id, action_record in actions.items():
        input_event = inputs[action_record["entityMetadata"]["triggerEventId"]]
        observations = [
            record for record in components if record["parentExecutionId"] == action_id
        ]
        created = [record for record in observations if record["status"] == "created"]
        assert len(created) == 2
        assert (
            created[0]["entityMetadata"]
            == created[1]["entityMetadata"]
            == {"request": "shared"}
        )
        completed = next(
            record for record in observations if record["status"] == "success"
        )
        caller_ids = completed["entityMetadata"]["callerExecutionIds"]
        assert len(set(caller_ids.values())) == 2
        for status, execution_id in caller_ids.items():
            assert isinstance(execution_id, str)
            assert execution_id.strip()
            call_reports = [
                record
                for record in observations
                if record["executionId"] == execution_id
            ]
            assert [record["status"] for record in call_reports] == [
                "created",
                "started",
                status,
                status,
            ]
            for record in call_reports:
                assert record["entityName"] == "shared_tool"
                assert record["inputRunId"] == input_event["inputRunId"]
                assert record["businessKey"] == action_record["businessKey"]
                assert record["agentName"] == action_record["agentName"]
                expected_metadata = {"request": "shared"}
                if record["status"] in {"success", "failed"}:
                    expected_metadata.update(
                        completed=True, callerExecutionIds=caller_ids
                    )
                assert record["entityMetadata"] == expected_metadata
                if record["status"] == "failed":
                    assert record["problemCategory"] == "test_call_failed"
                    assert record["attributes"] == {
                        "errorType": "builtins.ValueError",
                        "errorMessage": "reported component failure",
                    }

    for output_event in outputs:
        metadata = output_event["entityMetadata"]
        input_event = inputs[metadata["upstreamEventId"]]
        assert metadata["producerExecutionId"] in actions
        assert output_event["inputRunId"] == input_event["inputRunId"]
        assert output_event["attributes"] == {
            "output": {"processed_review": input_event["attributes"]["input"]["review"]}
        }


def test_python_execution_identity_survives_await_and_action_context_switch(
    tmp_path: Path,
) -> None:
    """A real suspended call retains its ID and parent after another Action reports."""
    gate_dir = tmp_path / "gates"
    gate_dir.mkdir()
    input_path = tmp_path / "input.jsonl"
    input_path.write_text(
        json.dumps({"id": 1, "gate_dir": str(gate_dir)}) + "\n", encoding="utf-8"
    )
    records = _read_log_records(
        _run_trace_logging_pipeline(
            tmp_path,
            targets=[TraceLogTarget.all()],
            agent=PythonAwaitExecutionReportingAgent(),
            input_path=input_path,
        )
    )
    [input_event] = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == InputEvent.EVENT_TYPE
    ]
    action_starts = [
        record
        for record in records
        if record["entityType"] == "action" and record["status"] == "started"
    ]
    assert len(action_starts) == 2
    actions = {record["executionId"]: record for record in action_starts}
    assert len(actions) == 2
    assert {record["entityName"] for record in actions.values()} == {
        "await_result",
        "release_result",
    }
    components = [
        record
        for record in records
        if record["entityType"] == ExecutionEntityTypes.TOOL
    ]
    # The release report must occur between the caller's start and completion:
    # the awaited function cannot return until the sibling Action opens its gate.
    assert [
        (actions[record["parentExecutionId"]]["entityName"], record["status"])
        for record in components
    ] == [
        ("await_result", "started"),
        ("release_result", "started"),
        ("release_result", "success"),
        ("await_result", "success"),
    ]
    call_ids = {
        record["parentExecutionId"]: record["executionId"]
        for record in components
        if record["status"] == "started"
    }
    assert len(set(call_ids.values())) == 2
    for record in components:
        action_record = actions[record["parentExecutionId"]]
        assert record["executionId"] == call_ids[action_record["executionId"]]
        assert record["entityName"] == "shared_tool"
        assert record.get("entityMetadata", {}) == {}
        assert (
            record["inputRunId"]
            == action_record["inputRunId"]
            == input_event["inputRunId"]
        )
        assert record["businessKey"] == action_record["businessKey"]
        assert record["agentName"] == action_record["agentName"]
        assert (
            action_record["entityMetadata"]["triggerEventId"]
            == input_event["entityMetadata"]["eventId"]
        )

    [output_event] = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    awaited_action = next(
        action for action in actions.values() if action["entityName"] == "await_result"
    )
    assert (
        output_event["entityMetadata"]["producerExecutionId"]
        == awaited_action["executionId"]
    )
    assert output_event["inputRunId"] == input_event["inputRunId"]
    assert output_event["attributes"]["output"] == {
        "executionId": call_ids[awaited_action["executionId"]],
        "result": "released",
    }


def test_llm_trace_records_per_call_usage(tmp_path: Path) -> None:
    """Trace the real chat action and preserve its response through the Event bridge."""
    inputs = [
        {
            "id": 1,
            "response_metadata": {
                "model_name": "request-alias",
                "promptTokens": 5,
                "completionTokens": 0,
            },
        },
        {"id": 2, "response_metadata": {"model_name": "request-alias"}},
    ]
    input_path = tmp_path / "llm-input.jsonl"
    input_path.write_text(
        "".join(json.dumps(value) + "\n" for value in inputs), encoding="utf-8"
    )
    records = _read_log_records(
        _run_trace_logging_pipeline(
            tmp_path,
            targets=[TraceLogTarget.all()],
            agent=PythonLlmTraceUsageAgent(),
            input_path=input_path,
        )
    )
    input_events = {
        record["inputRunId"]: record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == InputEvent.EVENT_TYPE
    }
    actions = {
        record["executionId"]: record
        for record in records
        if record["entityType"] == "action" and record["status"] == "started"
    }
    chat_requests = {
        record["entityMetadata"]["eventId"]: record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == ChatRequestEvent.EVENT_TYPE
    }
    llm_records = [
        record for record in records if record["entityType"] == ExecutionEntityTypes.LLM
    ]
    outputs = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    assert len(input_events) == len(chat_requests) == len(outputs) == 2
    assert len(llm_records) == 4
    assert len({record["executionId"] for record in llm_records}) == 2

    for run_id, input_event in input_events.items():
        started, succeeded = [
            record for record in llm_records if record["inputRunId"] == run_id
        ]
        assert [started["status"], succeeded["status"]] == ["started", "success"]
        assert started["entityMetadata"] == {"model": "request-alias"}
        expected = (
            {
                "model": "request-alias",
                "usage": {"promptTokens": 5, "completionTokens": 0},
            }
            if input_event["attributes"]["input"]["id"] == 1
            else {"model": "request-alias"}
        )
        assert succeeded.get("entityMetadata", {}) == expected
        assert started["executionId"]
        assert succeeded["executionId"] == started["executionId"]
        assert succeeded["parentExecutionId"] == started["parentExecutionId"]
        action_record = actions[started["parentExecutionId"]]
        request = chat_requests[action_record["entityMetadata"]["triggerEventId"]]
        assert request["attributes"]["model"] == "offline_model"
        assert request["inputRunId"] == action_record["inputRunId"] == run_id
        for record in (started, succeeded):
            assert record["entityName"] == "offline_model"
            assert record["businessKey"] == action_record["businessKey"]
            assert record["agentName"] == action_record["agentName"]

    for output in outputs:
        input_value = input_events[output["inputRunId"]]["attributes"]["input"]
        response = ChatResult.model_validate(output["attributes"]["output"]["response"])
        expected = input_value["response_metadata"]
        assert response.text == "reply"
        assert response.model == expected["model_name"]
        assert response.usage == TokenUsage(
            prompt_tokens=expected.get("promptTokens"),
            completion_tokens=expected.get("completionTokens"),
        )
