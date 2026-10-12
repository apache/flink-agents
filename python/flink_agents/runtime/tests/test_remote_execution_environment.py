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
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import MagicMock, patch

import cloudpickle
import pytest
import yaml
from pyflink.common import Row
from pyflink.common.typeinfo import ExternalTypeInfo, Types
from pyflink.table import DataTypes, Schema

from flink_agents.api.agents.agent import Agent
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.output_type_utils import (
    infer_row_type_info,
    schema_to_row_type_info,
)
from flink_agents.runtime.remote_execution_environment import (
    RemoteAgentBuilder,
    RemoteExecutionEnvironment,
    _row_types_equal,
)
from flink_agents.runtime.tests.output_type_fixtures import DcOutput, ModelOutput

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


def test_apply_sends_plan_to_java_validator() -> None:
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
    assert class_name == "org.apache.flink.agents.plan.AgentPlanJsonValidator"
    assert method_name == "validateAgentPlan"
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


def test_apply_wraps_java_validation_failure() -> None:
    builder = _remote_builder()
    agent = _agent_with_conditions(["_input_event"])
    bridge_error = RuntimeError("gateway failed")

    with (
        patch(
            "flink_agents.runtime.remote_execution_environment.invoke_method",
            side_effect=bridge_error,
        ),
        pytest.raises(
            RuntimeError, match="Java AgentPlan JSON validation failed"
        ) as error,
    ):
        builder.apply(agent)

    assert error.value.__cause__ is bridge_error


def test_apply_rejects_structure_before_java_validation() -> None:
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


def test_to_datastream_submits_java_validated_plan_json() -> None:
    config = AgentConfiguration({"plan.version": "validated"})
    builder = RemoteAgentBuilder(
        input=MagicMock(), config=config, resources={}, agents={}
    )
    agent = _agent_with_conditions(["_input_event"])

    with (
        patch(
            "flink_agents.runtime.remote_execution_environment.invoke_method",
            side_effect=[None, MagicMock()],
        ) as invoke,
        patch("flink_agents.runtime.remote_execution_environment.DataStream"),
    ):
        builder.apply(agent)
        validated_plan_json = invoke.call_args_list[0].args[3][0]
        config.set_str("plan.version", "mutated")
        builder.to_datastream()

    submitted_plan_json = invoke.call_args_list[1].args[3][1]
    assert submitted_plan_json == validated_plan_json
    assert json.loads(submitted_plan_json)["config"]["conf_data"]["plan.version"] == (
        "validated"
    )


def _output_schema() -> Schema:
    return (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("label", DataTypes.STRING())
        .column("score", DataTypes.DOUBLE())
        .build()
    )


@contextmanager
def _applied_builder(t_env=None) -> Iterator[tuple]:
    """Yield an applied builder with a capturable raw output stream.

    ``invoke_method`` is patched so plan validation passes and ``connectToAgent``
    returns a stub; ``DataStream`` is patched so the cached raw stream is
    ``raw_mock``. A test then inspects each terminal's conversion operator through
    ``raw_mock.map.call_args_list`` without a Flink runtime.
    """
    if t_env is None:
        t_env = MagicMock()
    builder = RemoteAgentBuilder(
        input=MagicMock(),
        config=AgentConfiguration(),
        t_env=t_env,
        resources={},
        agents={},
    )
    agent = _agent_with_conditions(["_input_event"])
    raw_mock = MagicMock(name="raw_output")
    with (
        patch(
            "flink_agents.runtime.remote_execution_environment.invoke_method",
            side_effect=[None, MagicMock()],
        ) as invoke_mock,
        patch(
            "flink_agents.runtime.remote_execution_environment.DataStream",
            return_value=raw_mock,
        ),
    ):
        builder.apply(agent)
        yield builder, raw_mock, t_env, invoke_mock


def _last_map(raw_mock) -> tuple:
    """Return ``(func, output_type)`` of the most recent ``raw.map`` call."""
    call = raw_mock.map.call_args_list[-1]
    return call.args[0], call.kwargs.get("output_type")


def test_to_datastream_raw_view_is_untyped():
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream()
    func, output_type = _last_map(raw_mock)
    assert output_type is None
    assert func(cloudpickle.dumps({"id": 1})) == {"id": 1}


def test_to_datastream_scalar_type():
    long_ti = Types.LONG()
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream(long_ti)
    func, output_type = _last_map(raw_mock)
    assert output_type is long_ti
    assert func(cloudpickle.dumps(42)) == 42


def test_to_datastream_structured_model_yields_instances():
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream(ModelOutput)
    func, output_type = _last_map(raw_mock)
    # A Pydantic model has no native Flink type: the stream stays pickle-typed.
    assert output_type is None
    payload = cloudpickle.dumps({"id": 1, "label": "a", "score": 1.5})
    assert func(payload) == ModelOutput(id=1, label="a", score=1.5)
    # The closure captures no py4j object, so it survives a pickle round-trip.
    restored = cloudpickle.loads(cloudpickle.dumps(func))
    assert restored(payload) == ModelOutput(id=1, label="a", score=1.5)


def test_to_datastream_row_type_info_yields_rows():
    row_ti = infer_row_type_info(ModelOutput)
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream(row_ti)
    func, output_type = _last_map(raw_mock)
    assert isinstance(output_type, ExternalTypeInfo)
    payload = cloudpickle.dumps({"id": 1, "label": "a", "score": 1.5})
    assert list(func(payload)) == [1, "a", 1.5]
    # The closure captures only the picklable shape, not the py4j RowTypeInfo.
    restored = cloudpickle.loads(cloudpickle.dumps(func))
    assert list(restored(payload)) == [1, "a", 1.5]


def test_to_datastream_external_row_type_info_yields_rows():
    # An ExternalTypeInfo-wrapped RowTypeInfo (the form a Table terminal hands
    # back) is unwrapped to its inner row type before the row conversion.
    wrapped = ExternalTypeInfo(infer_row_type_info(ModelOutput))
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream(wrapped)
    func, output_type = _last_map(raw_mock)
    assert isinstance(output_type, ExternalTypeInfo)
    payload = cloudpickle.dumps({"id": 1, "label": "a", "score": 1.5})
    assert list(func(payload)) == [1, "a", 1.5]


def test_to_datastream_caches_raw_stream_without_welding_type():
    long_ti = Types.LONG()
    with _applied_builder() as (builder, raw_mock, _, invoke_mock):
        builder.to_datastream()
        builder.to_datastream(long_ti)
    # The agent operator is connected once; each view maps its own conversion.
    assert invoke_mock.call_count == 2
    assert raw_mock.map.call_count == 2
    first_type = raw_mock.map.call_args_list[0].kwargs.get("output_type")
    second_type = raw_mock.map.call_args_list[1].kwargs.get("output_type")
    assert first_type is None
    assert second_type is long_ti


def test_multiple_typed_datastreams_of_different_types_coexist():
    # With no single-declaration guard, one execution exposes several independent
    # typed streams. Each typed call binds its own type through a separate
    # conversion operator on the shared raw stream, and the unrestricted view
    # stays untyped.
    with _applied_builder() as (builder, raw_mock, _, _):
        builder.to_datastream()
        builder.to_datastream(ModelOutput)
        builder.to_datastream(DcOutput)
    # Raw view + two typed streams = three independent operators; no conflict.
    assert raw_mock.map.call_count == 3
    assert raw_mock.map.call_args_list[0].kwargs.get("output_type") is None


def test_to_table_rejects_type_declaration_in_schema_slot():
    # The builder's 'schema' slot is Schema-only; a type declaration must be
    # passed as output_type=... instead.
    with (
        _applied_builder() as (builder, _, _, _),
        pytest.raises(TypeError, match="accepts a Table Schema only"),
    ):
        builder.to_table(ModelOutput)


def test_to_table_schema_only_builds_row_stream():
    schema = _output_schema()
    with _applied_builder() as (builder, raw_mock, t_env, _):
        builder.to_table(schema)
    func, output_type = _last_map(raw_mock)
    assert isinstance(output_type, ExternalTypeInfo)
    payload = cloudpickle.dumps({"id": 1, "label": "a", "score": 1.5})
    assert isinstance(func(payload), Row)
    # The caller's Schema is passed through to preserve Table-domain information.
    assert t_env.from_data_stream.call_args.args[1] is schema


def test_to_table_output_type_derives_schema():
    with _applied_builder() as (builder, _, t_env, _):
        builder.to_table(output_type=ModelOutput)
    derived = t_env.from_data_stream.call_args.args[1]
    assert isinstance(derived, Schema)
    assert _row_types_equal(
        schema_to_row_type_info(derived), infer_row_type_info(ModelOutput)
    )


def test_to_table_cross_check_matching():
    schema = _output_schema()
    output_type = ExternalTypeInfo(infer_row_type_info(ModelOutput))
    with _applied_builder() as (builder, _, t_env, _):
        builder.to_table(schema=schema, output_type=output_type)
    assert t_env.from_data_stream.call_args.args[1] is schema


def test_to_table_cross_check_mismatched():
    schema = _output_schema()
    output_type = ExternalTypeInfo(infer_row_type_info(DcOutput))
    with (
        _applied_builder() as (builder, _, _, _),
        pytest.raises(ValueError, match="different row types"),
    ):
        builder.to_table(schema=schema, output_type=output_type)


def test_to_table_requires_schema_or_output_type():
    # Neither a schema nor an output type: the terminal cannot derive a row type.
    with (
        _applied_builder() as (builder, _, _, _),
        pytest.raises(ValueError, match="at least one of 'schema' or 'output_type'"),
    ):
        builder.to_table()


def test_to_table_rejects_schema_in_output_type_slot():
    # The 'output_type' slot takes a type declaration; a Table Schema belongs in
    # the schema= slot, mirroring the schema-slot rejection above.
    with (
        _applied_builder() as (builder, _, _, _),
        pytest.raises(TypeError, match="pass a Table Schema as schema="),
    ):
        builder.to_table(output_type=_output_schema())
