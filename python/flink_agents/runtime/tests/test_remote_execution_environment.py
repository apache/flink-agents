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
import os
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import yaml

from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.remote_execution_environment import (
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


def _verify_config(config: AgentConfiguration) -> None:
    assert config.get_str("database.host") == "localhost"
    assert config.get_int("database.port") == 5432
    assert config.get_str("api.endpoint") == "/api/v1"
    assert config.get_float("api.timeout") == 30.0
    assert config.get_bool("debug") is True
