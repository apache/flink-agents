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
"""Recover Python timer Actions in a real MiniCluster after an injected failure."""

import json
import socket
import sys
import sysconfig
import time
from pathlib import Path
from urllib.request import urlopen

import pytest
from pyflink.common import Configuration, Duration, Types, WatermarkStrategy
from pyflink.common.watermark_strategy import TimestampAssigner
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat
from pyflink.datastream.functions import MapFunction, ProcessFunction

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.timer_recovery_agent import (
    TIMER_KINDS,
    TimerRecoveryAgent,
)


class FailOnce(MapFunction):
    def __init__(self, directory: str) -> None:
        self.directory = directory

    def open(self, runtime_context):
        if runtime_context.get_attempt_number() > 0:
            (Path(self.directory) / "recovered").write_text(
                str(runtime_context.get_attempt_number())
            )

    def map(self, value):
        if value == "fail":
            marker = Path(self.directory) / "failed"
            if not marker.exists():
                marker.touch()
                msg = "Injected failure after the timer checkpoint completed"
                raise RuntimeError(msg)
        return value


class InputTimestamp(TimestampAssigner):
    def extract_timestamp(self, value, record_timestamp):
        # Only the replayed failure record advances event time past the timer.
        return 100 if value == "fail" else 0


class RecordResult(ProcessFunction):
    def __init__(self, directory: str) -> None:
        self.directory = directory

    def process_element(self, value, ctx):
        if value == "event_time":
            assert ctx.timestamp() == 10
            assert ctx.timer_service().current_watermark() < 10
        elif value == "current_run":
            assert ctx.timestamp() == 0
        (Path(self.directory) / f"result-{value}").write_text("ok")
        yield value


def _await(predicate, job, description: str, timeout: float = 120) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return
        execution = job.get_job_execution_result()
        if execution.done():
            execution.result()
            pytest.fail(f"Job finished while waiting for {description}")
        time.sleep(0.1)
    pytest.fail(f"Timed out waiting for {description}")


def test_pending_python_timers_survive_job_restart(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    python_path = f"{Path(__file__).parents[3]}:{sysconfig.get_paths()['purelib']}"
    monkeypatch.setenv("PYTHONPATH", python_path)
    inputs = tmp_path / "inputs"
    inputs.mkdir()
    (inputs / "initial.txt").write_text("\n".join(TIMER_KINDS) + "\n")
    with socket.socket() as sock:
        sock.bind(("localhost", 0))
        rest_port = sock.getsockname()[1]
    config = Configuration()
    config.set_string("python.pythonpath", python_path)
    config.set_boolean("local.start-webserver", True)
    config.set_integer("rest.port", rest_port)
    config.set_string("execution.checkpointing.interval", "200 ms")
    config.set_string(
        "execution.checkpointing.dir", (tmp_path / "checkpoints").as_uri()
    )
    config.set_string("restart-strategy.type", "fixed-delay")
    config.set_integer("restart-strategy.fixed-delay.attempts", 1)
    config.set_string("restart-strategy.fixed-delay.delay", "100 ms")
    config.set_string("jobmanager.execution.failover-strategy", "full")
    config.set_integer("python.fn-execution.bundle.size", 1)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_python_executable(sys.executable)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    stream = (
        env.from_source(
            FileSource.for_record_stream_format(
                StreamFormat.text_line_format(), inputs.as_uri()
            )
            .monitor_continuously(Duration.of_millis(100))
            .build(),
            WatermarkStrategy.no_watermarks(),
            "timer-recovery-input",
        )
        .map(FailOnce(str(tmp_path)))
        .assign_timestamps_and_watermarks(
            WatermarkStrategy.for_monotonous_timestamps().with_timestamp_assigner(
                InputTimestamp()
            )
        )
    )
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set_str("timer_test_dir", str(tmp_path))
    output = (
        agents.from_datastream(stream, key_selector=lambda kind: kind)
        .apply(TimerRecoveryAgent())
        .to_datastream()
    )
    output.process(RecordResult(str(tmp_path)), output_type=Types.STRING()).print()
    job = env.execute_async("Python timer checkpoint recovery")
    checkpoints_url = (
        f"http://localhost:{rest_port}/jobs/{job.get_job_id()}/checkpoints"
    )

    def checkpoints() -> dict:
        with urlopen(checkpoints_url, timeout=10) as response:
            return json.load(response)

    try:
        _await(
            lambda: all((tmp_path / f"ready-{kind}").exists() for kind in TIMER_KINDS),
            job,
            "committed timer registrations",
        )
        # A checkpoint triggered after this observation includes all registrations.
        registered_at = int(time.time() * 1000)

        def checkpoint_after_registration() -> bool:
            completed = checkpoints()["latest"].get("completed")
            return (
                completed is not None and completed["trigger_timestamp"] > registered_at
            )

        _await(checkpoint_after_registration, job, "a completed timer checkpoint")
        deadlines = [
            int((tmp_path / f"ready-{kind}").read_text())
            for kind in ("new_run", "current_run")
        ]
        assert time.time() * 1000 < min(deadlines), "Checkpoint missed pending timers"
        (inputs / "failure.txt").write_text("fail\n")
        _await(lambda: (tmp_path / "recovered").exists(), job, "job restart")
        assert (tmp_path / "failed").exists()
        assert (tmp_path / "recovered").read_text() == "1"
        _await(
            lambda: checkpoints()["counts"]["restored"] == 1,
            job,
            "checkpoint restore statistics",
        )
        _await(
            lambda: all((tmp_path / f"result-{kind}").exists() for kind in TIMER_KINDS),
            job,
            "all three restored timers",
        )
        for kind in TIMER_KINDS:
            assert (tmp_path / f"registered-{kind}").read_text() == "registered\n"
    finally:
        if not job.get_job_execution_result().done():
            job.cancel().result()
