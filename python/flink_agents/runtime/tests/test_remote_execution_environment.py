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
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import yaml

from flink_agents.api.agents.agent import Agent
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.remote_execution_environment import (
    RemoteAgentBuilder,
    RemoteExecutionEnvironment,
)

test_data = {
    "agent": {
        "database": {
            "host": "localhost",
            "port": 5432,
        },
        "api": {"endpoint": "/api/v1", "timeout": 30.0},
        "debug": True,
    }
}


def _action(event: Event, ctx: RunnerContext) -> None:
    pass


def _agent_with_conditions(trigger_conditions: list[str]) -> Agent:
    agent = Agent()
    agent.add_action(
        name="handle",
        trigger_conditions=trigger_conditions,
        func=_action,
    )
    return agent


def _remote_builder() -> RemoteAgentBuilder:
    with patch(
        "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
    ):
        remote_env = RemoteExecutionEnvironment(env=MagicMock())
    return remote_env.from_datastream(
        input=MagicMock(), key_selector=lambda record: record["key"]
    )


def test_remote_execution_environment_load_config_file() -> None:
    """Test RemoteExecutionEnvironment loads config from config.yaml."""
    # Create a temporary directory with config.yaml
    with tempfile.TemporaryDirectory() as temp_dir:
        config_file = Path(temp_dir) / "config.yaml"
        with config_file.open("w") as f:
            yaml.dump(test_data, f)

        # Set FLINK_CONF_DIR environment variable
        original_env = os.environ.get("FLINK_CONF_DIR")
        try:
            os.environ["FLINK_CONF_DIR"] = temp_dir

            # Mock StreamExecutionEnvironment
            mock_stream_env = MagicMock()

            # Create RemoteExecutionEnvironment instance
            with patch(
                "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
            ):
                remote_env = RemoteExecutionEnvironment(env=mock_stream_env)

                # Verify that configuration was loaded correctly
                _verify_config(remote_env.get_config())

        finally:
            # Restore original environment variable
            if original_env is None:
                os.environ.pop("FLINK_CONF_DIR", None)
            else:
                os.environ["FLINK_CONF_DIR"] = original_env


def test_remote_execution_environment_load_legacy_config_file() -> None:
    """Test RemoteExecutionEnvironment loads legacy flink-conf.yaml."""
    # Create a temporary directory with flink-conf.yaml (legacy name)
    with tempfile.TemporaryDirectory() as temp_dir:
        legacy_config_file = Path(temp_dir) / "flink-conf.yaml"
        with legacy_config_file.open("w") as f:
            yaml.dump(test_data, f)

        # Set FLINK_CONF_DIR environment variable
        original_env = os.environ.get("FLINK_CONF_DIR")
        try:
            os.environ["FLINK_CONF_DIR"] = temp_dir

            # Mock StreamExecutionEnvironment and capture logging
            mock_stream_env = MagicMock()

            # Capture warning log about using legacy config file
            with patch(
                "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
            ):
                with patch(
                    "flink_agents.runtime.remote_execution_environment.logging"
                ) as mock_logging:
                    # Create RemoteExecutionEnvironment instance
                    remote_env = RemoteExecutionEnvironment(env=mock_stream_env)

                    # Verify that a warning was logged about using legacy config
                    assert mock_logging.warning.called
                    warning_call_args = mock_logging.warning.call_args[0][0]
                    assert "legacy config file" in warning_call_args.lower()
                    assert "flink-conf.yaml" in warning_call_args

                # Verify that configuration was loaded correctly
                config = remote_env.get_config()
                _verify_config(config)

        finally:
            # Restore original environment variable
            if original_env is None:
                os.environ.pop("FLINK_CONF_DIR", None)
            else:
                os.environ["FLINK_CONF_DIR"] = original_env


def test_remote_execution_environment_prioritizes_legacy_config() -> None:
    """Test RemoteExecutionEnvironment prioritizes flink-conf.yaml over config.yaml."""
    # Create a temporary directory with both config files
    with tempfile.TemporaryDirectory() as temp_dir:
        # Create config.yaml with one set of values
        config_file = Path(temp_dir) / "config.yaml"
        config_data = {
            "agent": {
                "database": {
                    "host": "config-host",
                    "port": 9999,
                },
            }
        }
        with config_file.open("w") as f:
            yaml.dump(config_data, f)

        # Create flink-conf.yaml with different values
        legacy_config_file = Path(temp_dir) / "flink-conf.yaml"
        legacy_data = {
            "agent": {
                "database": {
                    "host": "legacy-host",
                    "port": 1234,
                },
            }
        }
        with legacy_config_file.open("w") as f:
            yaml.dump(legacy_data, f)

        # Set FLINK_CONF_DIR environment variable
        original_env = os.environ.get("FLINK_CONF_DIR")
        try:
            os.environ["FLINK_CONF_DIR"] = temp_dir

            # Mock StreamExecutionEnvironment
            mock_stream_env = MagicMock()

            # Create RemoteExecutionEnvironment instance
            with patch(
                "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
            ):
                remote_env = RemoteExecutionEnvironment(env=mock_stream_env)

                # Verify that configuration was loaded from flink-conf.yaml (legacy)
                config = remote_env.get_config()
                assert config.get_str("database.host") == "legacy-host"
                assert config.get_int("database.port") == 1234

        finally:
            # Restore original environment variable
            if original_env is None:
                os.environ.pop("FLINK_CONF_DIR", None)
            else:
                os.environ["FLINK_CONF_DIR"] = original_env


def test_execute_with_job_name() -> None:
    """Test that execute() passes job_name to StreamExecutionEnvironment."""
    mock_stream_env = MagicMock()

    with patch(
        "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
    ):
        remote_env = RemoteExecutionEnvironment(env=mock_stream_env)
        remote_env.execute(job_name="my-test-job")

    mock_stream_env.execute.assert_called_once_with(job_name="my-test-job")


def test_execute_without_job_name() -> None:
    """Test execute() passes None to StreamExecutionEnvironment when no job_name."""
    mock_stream_env = MagicMock()

    with patch(
        "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
    ):
        remote_env = RemoteExecutionEnvironment(env=mock_stream_env)
        remote_env.execute()

    mock_stream_env.execute.assert_called_once_with(job_name=None)


def test_apply_by_unknown_name_errors() -> None:
    """Applying an unregistered agent name raises ValueError before execution.

    The guard fires at apply() time on the remote builder, so no cluster is
    started and no job is submitted. The Flink environment and input datastream
    are never exercised to reach the guard, so both are mocked.
    """
    with patch(
        "flink_agents.runtime.remote_execution_environment.StreamExecutionEnvironment"
    ):
        remote_env = RemoteExecutionEnvironment(env=MagicMock())

    builder = remote_env.from_datastream(
        input=MagicMock(), key_selector=lambda record: record["key"]
    )

    with pytest.raises(ValueError, match="ghost"):
        builder.apply("ghost")


def test_apply_sends_plan_to_java_preflight() -> None:
    builder = _remote_builder()
    agent = _agent_with_conditions(["type == EventType.InputEvent && input > 2"])

    with patch(
        "flink_agents.runtime.remote_execution_environment.invoke_method",
        return_value=None,
    ) as invoke:
        returned = builder.apply(agent)

    assert returned is builder
    invoke.assert_called_once()
    _, class_name, method_name, args, parameter_types = invoke.call_args.args
    assert class_name == "org.apache.flink.agents.plan.AgentPlanPreflight"
    assert method_name == "findValidationError"
    assert parameter_types == ["java.lang.String"]
    payload = json.loads(args[0])
    assert payload["actions"]["handle"]["trigger_conditions"] == [
        "type == EventType.InputEvent && input > 2"
    ]


def test_apply_surfaces_java_error_and_allows_retry() -> None:
    builder = _remote_builder()
    agent = _agent_with_conditions(["type =="])
    error_message = (
        "Invalid trigger condition #1 for action 'handle' from source "
        '"type ==": syntax error'
    )

    with patch(
        "flink_agents.runtime.remote_execution_environment.invoke_method",
        side_effect=[error_message, None],
    ) as invoke:
        with pytest.raises(ValueError) as error:
            builder.apply(agent)
        returned = builder.apply(agent)

    assert str(error.value) == error_message
    assert returned is builder
    assert invoke.call_count == 2


def test_apply_wraps_java_preflight_failure() -> None:
    builder = _remote_builder()
    agent = _agent_with_conditions(["_input_event"])
    bridge_error = RuntimeError("gateway failed")

    with (
        patch(
            "flink_agents.runtime.remote_execution_environment.invoke_method",
            side_effect=bridge_error,
        ),
        pytest.raises(RuntimeError, match="Java AgentPlan preflight failed") as error,
    ):
        builder.apply(agent)

    assert error.value.__cause__ is bridge_error


def test_apply_rejects_structure_before_java_preflight() -> None:
    builder = _remote_builder()
    agent = _agent_with_conditions(["   "])

    with (
        patch(
            "flink_agents.runtime.remote_execution_environment.invoke_method"
        ) as invoke,
        pytest.raises(ValueError, match="Invalid trigger condition #1"),
    ):
        builder.apply(agent)

    invoke.assert_not_called()


def _verify_config(config: AgentConfiguration) -> None:
    assert config.get_str("database.host") == "localhost"
    assert config.get_int("database.port") == 5432
    assert config.get_str("api.endpoint") == "/api/v1"
    assert config.get_float("api.timeout") == 30.0
    assert config.get_bool("debug") is True
