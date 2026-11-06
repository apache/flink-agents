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
"""Real PyFlink checkpoint progress for idle, busy, and blocked Agent keys.

Run this test under both pre-21 and 21+ JVMs. Python coroutines should allow
checkpoint progress independently of Java's Continuation support. No external
model, Kafka, or Fluss service is needed. As with other JDK 21 Agent jobs, the
gateway JVM needs --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED.
"""

import json
import socket
import time
from pathlib import Path
from typing import Callable
from urllib.request import urlopen

from pyflink.common import Configuration, Duration, WatermarkStrategy
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat
from pyflink.java_gateway import get_gateway

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.checkpoint_agent import (
    CheckpointAgent,
    mark_submitted,
    record_output,
)


def _await(condition: Callable[[], bool], description: str) -> None:
    deadline = time.monotonic() + 40
    while not condition():
        assert time.monotonic() < deadline, description
        time.sleep(0.1)


def test_checkpoint_progress_while_idle_and_across_busy_keys(tmp_path: Path) -> None:
    """Checkpoint suspended calls and queued inputs while another key progresses."""
    # The local web UI exposes actual completed-checkpoint metadata. An empty
    # source lets us establish a baseline before admitting the gated input.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    config = Configuration()
    config.set_integer("rest.port", port)
    config.set_string("rest.bind-address", "127.0.0.1")
    config.set_string("execution.checkpointing.interval", "1s")
    config.set_string("execution.checkpointing.timeout", "5s")
    config.set_string("execution.checkpointing.dir", (tmp_path / "cp").as_uri())
    config.set_string("restart-strategy.type", "none")
    config.set_string("state.backend.type", "hashmap")
    j_env = get_gateway().jvm.org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
    env = StreamExecutionEnvironment(
        j_env.createLocalEnvironmentWithWebUI(config._j_configuration)
    )
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    env.get_checkpoint_config().set_tolerable_checkpoint_failure_number(10)
    input_dir = tmp_path / "input"
    input_dir.mkdir()
    source = (
        FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), str(input_dir)
        )
        .monitor_continuously(Duration.of_millis(100))
        .build()
    )
    # Agents' Python input boundary expects pickle, whereas FileSource emits
    # native Java strings. Use the default Python map output serializer.
    inputs = env.from_source(source, WatermarkStrategy.no_watermarks(), "trigger").map(
        mark_submitted
    )
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    (
        agents.from_datastream(
            inputs, key_selector=lambda value: Path(value).parent.name
        )
        .apply(CheckpointAgent())
        .to_datastream()
        .map(record_output)
        .print()
    )
    job = env.execute_async("python-checkpoint-e2e")
    result_future = job._j_job_client.getJobExecutionResult()
    url = f"http://127.0.0.1:{port}/jobs/{job.get_job_id()}/checkpoints"

    def check_job() -> None:
        if result_future.isDone():
            # Surface the actual job failure instead of a refused REST connection
            # after a failed local job has already shut its MiniCluster down.
            result_future.get()
            msg = "The unbounded checkpoint job terminated early"
            raise AssertionError(msg)

    def await_condition(condition: Callable[[], bool], description: str) -> None:
        def checked() -> bool:
            check_job()
            return condition()

        _await(checked, description)

    def send(key: str, name: str, *, blocked: bool = False) -> Path:
        record = tmp_path / "records" / key / name
        record.mkdir(parents=True)
        if not blocked:
            (record / "release").touch()
        (input_dir / f"{key}-{name}").write_text(str(record) + "\n", encoding="utf-8")
        await_condition(lambda: (record / "submitted").exists(), "input not submitted")
        return record

    def expect_output(record: Path, count: int) -> None:
        await_condition(lambda: (record / "output").exists(), "output not received")
        assert (record / "output").read_text(encoding="utf-8") == str(count)

    def completed_after(timestamp: int) -> list[dict]:
        check_job()
        with urlopen(url, timeout=5) as response:
            stats = json.load(response)
        return [
            cp
            for cp in stats["history"]
            if cp["status"] == "COMPLETED" and cp["trigger_timestamp"] > timestamp
        ]

    try:
        _await(lambda: bool(completed_after(0)), "baseline checkpoint did not complete")
        held = send("a", "held", blocked=True)
        await_condition(lambda: (held / "entered").exists(), "async call did not start")
        queued = send("a", "queued")
        entered_at = time.time_ns() // 1_000_000
        for count in (1, 2):
            expect_output(send("b", f"fast-{count}"), count)
        _await(
            lambda: len(completed_after(entered_at)) >= 2,
            "checkpoints did not complete while Python coroutine was suspended",
        )
        assert not (held / "output").exists()
        assert not (queued / "entered").exists()
        (held / "release").touch()
        expect_output(held, 1)
        expect_output(queued, 2)
        finished_at = time.time_ns() // 1_000_000
        _await(
            lambda: bool(completed_after(finished_at)),
            "checkpoint did not complete after Action finished",
        )
    finally:
        for record in (tmp_path / "records").glob("*/*"):
            (record / "release").touch()
        if not result_future.isDone():
            job.cancel().result()
