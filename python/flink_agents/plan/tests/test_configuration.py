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
import tempfile
from pathlib import Path

import pytest
import yaml

from flink_agents.api.configuration import ConfigOption
from flink_agents.api.core_options import (
    AgentConfigOptions,
    LoggerType,
)
from flink_agents.api.trace_log import TraceLogLevel, TraceLogScope, TraceLogTarget
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration


def test_load_configuration_from_file() -> None:
    """Test loading configuration from a YAML file."""
    # Create a temporary YAML file with test data
    test_data = {
        "agent": {
            "database": {
                "host": "localhost",
                "port": 5432,
                "credentials": {"username": "admin", "password": "secret"},
            },
            "api": {"endpoint": "/api/v1", "timeout": 30.0},
            "debug": True,
        }
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        yaml.dump(test_data, f)
        config_file = f.name

    try:
        # Load configuration
        config = AgentConfiguration()
        config.load_from_file(config_file)

        # Test that nested configuration is properly flattened
        assert config.get_str("database.host") == "localhost"
        assert config.get_int("database.port") == 5432
        assert config.get_str("database.credentials.username") == "admin"
        assert config.get_str("database.credentials.password") == "secret"
        assert config.get_str("api.endpoint") == "/api/v1"
        assert config.get_float("api.timeout") == 30.0
        assert config.get_bool("debug") is True
    finally:
        config_file = Path(config_file)
        config_file.unlink()


def test_load_configuration_without_path() -> None:
    """Test loading configuration without a path (should not change _conf_data)."""
    config = AgentConfiguration()
    # Initially _conf_data should be empty or unchanged
    original_conf_data = config.get_conf_data().copy()
    config.load_from_file(None)
    # _conf_data should remain unchanged
    assert config.get_conf_data() == original_conf_data


def test_load_configuration_with_invalid_file() -> None:
    """Test loading configuration with a non-existent file."""
    config = AgentConfiguration()
    with pytest.raises(FileNotFoundError):
        config.load_from_file("/path/to/nonexistent/file.yaml")


def test_load_configuration_with_invalid_yaml() -> None:
    """Test loading configuration with invalid YAML content."""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        f.write("invalid: yaml: content: [")
        config_file = f.name

    try:
        config = AgentConfiguration()
        with pytest.raises(yaml.YAMLError):
            config.load_from_file(config_file)
    finally:
        config_file = Path(config_file)
        config_file.unlink()


def test_get_int() -> None:
    """Test get_int method with various inputs."""
    config = AgentConfiguration(
        {"int_key": 42, "str_key": "123", "invalid_key": "not_an_int"}
    )

    # Test normal integer value
    assert config.get_int("int_key") == 42

    # Test string that can be converted to int
    assert config.get_int("str_key") == 123

    # Test default value when key is not found
    assert config.get_int("missing_key", 999) == 999

    # Test default value when no default specified
    assert config.get_int("missing_key") is None

    # Test invalid value that cannot be converted to int
    with pytest.raises(ValueError, match="Invalid value for invalid_key: not_an_int"):
        config.get_int("invalid_key")


def test_get_float() -> None:
    """Test get_float method with various inputs."""
    config = AgentConfiguration(
        {
            "float_key": 3.14,
            "int_key": 42,
            "str_key": "2.5",
            "invalid_key": "not_a_float",
        }
    )

    # Test normal float value
    assert config.get_float("float_key") == 3.14

    # Test int value converted to float
    assert config.get_float("int_key") == 42.0

    # Test string that can be converted to float
    assert config.get_float("str_key") == 2.5

    # Test default value when key is not found
    assert config.get_float("missing_key", 1.23) == 1.23

    # Test default value when no default specified
    assert config.get_float("missing_key") is None

    # Test invalid value that cannot be converted to float
    with pytest.raises(ValueError, match="Invalid value for invalid_key: not_a_float"):
        config.get_float("invalid_key")


def test_get_bool() -> None:
    """Test get_bool method with various inputs."""
    config = AgentConfiguration(
        {"bool_key": True, "false_key": False, "str_key": "true"}
    )

    # Test normal boolean values
    assert config.get_bool("bool_key") is True
    assert config.get_bool("false_key") is False

    # Test default value when key is not found
    assert config.get_bool("missing_key", True) is True

    # Test default value when no default specified
    assert config.get_bool("missing_key") is None

    # Note: bool() in Python behaves differently than might be expected
    # bool('true') is True, but that's Python behavior, not a bug in our code
    assert config.get_bool("str_key") is True


def test_get_str() -> None:
    """Test get_str method with various inputs."""
    config = AgentConfiguration({"str_key": "hello", "int_key": 42, "float_key": 3.14})

    # Test normal string value
    assert config.get_str("str_key") == "hello"

    # Test int value converted to string
    assert config.get_str("int_key") == "42"

    # Test float value converted to string
    assert config.get_str("float_key") == "3.14"

    # Test default value when key is not found
    assert config.get_str("missing_key", "default") == "default"

    # Test default value when no default specified
    assert config.get_str("missing_key") is None

    # Test None value
    assert config.get_str("none_key") is None


def test_get_with_config_option() -> None:
    data = {
        "config.str": "config.value",
        "config.int": 6789,
        "config.float": "45.5",
        "config.boolean": True,
    }

    config = AgentConfiguration(data)

    str_option = ConfigOption("config.str", str, "default_str")
    int_option = ConfigOption("config.int", int, 123)
    float_option = ConfigOption("config.float", float, 0.0)
    bool_option = ConfigOption("config.boolean", bool, False)

    assert config.get(str_option) == "config.value"
    assert config.get(int_option) == 6789
    assert config.get(float_option) == 45.5
    assert config.get(bool_option) is True

    missing_option = ConfigOption("missing.key1", int, 22)
    assert config.get(missing_option) == 22

    missing_key = ConfigOption("missing.key2", int, None)
    assert config.get(missing_key) is None


def test_get_with_default_value() -> None:
    default_str = ConfigOption("default.str", str, "default_value")
    default_int = ConfigOption("default.int", int, 100)
    default_double = ConfigOption("default.double", float, 2.5)
    default_targets = ConfigOption(
        "default.targets", list, [{"scope": "EVENT_ONLY", "level": "STANDARD"}]
    )

    config = AgentConfiguration()

    assert config.get(default_str) == "default_value"
    assert config.get(default_int) == 100
    assert config.get(default_double) == 2.5

    targets = config.get(default_targets)
    targets[0]["level"] = "OFF"
    targets.append({"scope": "ALL"})

    expected_targets = [{"scope": "EVENT_ONLY", "level": "STANDARD"}]
    assert config.get(default_targets) == expected_targets
    assert AgentConfiguration().get(default_targets) == expected_targets
    assert default_targets.get_default_value() == expected_targets


def test_get_with_null_and_default() -> None:
    nullable_str = ConfigOption("nullable.str", str, "default")

    config = AgentConfiguration()
    config.set_str("nullable.str", None)

    assert config.get(nullable_str) == "default"


def test_trace_log_defaults_and_empty_targets_survive_json() -> None:
    config = AgentConfiguration()
    assert config.get(AgentConfigOptions.TRACE_LOG_TARGETS) == [
        TraceLogTarget.events(TraceLogLevel.STANDARD)
    ]
    assert config.get(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE) is LoggerType.SLF4J

    config.set(AgentConfigOptions.TRACE_LOG_TARGETS, [])
    config.set(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE, LoggerType.FILE)
    restored = AgentConfiguration.model_validate_json(config.model_dump_json())
    assert restored.get(AgentConfigOptions.TRACE_LOG_TARGETS) == []
    assert restored.get(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE) is LoggerType.FILE


@pytest.mark.parametrize(
    ("targets", "expected"),
    [
        (
            [
                {"scope": "ALL", "level": "OFF"},
                {"scope": "EVENT_ONLY", "level": "STANDARD"},
                {"scope": {"event": "com.foo.*"}, "level": "VERBOSE"},
                {"scope": {"action": "process"}},
                {"scope": {"tool": "*"}},
                {"scope": {"entityType": "embedding", "entityName": "search"}},
            ],
            [
                TraceLogTarget.all(TraceLogLevel.OFF),
                TraceLogTarget.events(TraceLogLevel.STANDARD),
                TraceLogTarget.event("com.foo.*", TraceLogLevel.VERBOSE),
                TraceLogTarget.action("process"),
                TraceLogTarget.tools(),
                TraceLogTarget.entity("embedding", "search"),
            ],
        ),
        ([], []),
    ],
)
def test_trace_log_targets_survive_yaml_and_plan_json(
    tmp_path: Path, targets: list[dict], expected: list[TraceLogTarget]
) -> None:
    config_file = tmp_path / "agent.yaml"
    config_file.write_text(
        yaml.safe_dump({"agent": {"trace-log": {"targets": targets}}}),
        encoding="utf-8",
    )
    config = AgentConfiguration()
    config.load_from_file(str(config_file))
    assert config.get(AgentConfigOptions.TRACE_LOG_TARGETS) == expected

    typed_config = AgentConfiguration()
    typed_config.set(AgentConfigOptions.TRACE_LOG_TARGETS, expected)
    assert config.get_conf_data() == typed_config.get_conf_data()
    plan = AgentPlan(actions={}, config=config)
    serialized = plan.model_dump_json(serialize_as_any=True)
    restored = AgentPlan.model_validate_json(serialized)
    assert restored.config.get(AgentConfigOptions.TRACE_LOG_TARGETS) == expected
    assert json.loads(serialized)["config"]["conf_data"] == config.get_conf_data()


@pytest.mark.parametrize(
    "category", ["event", "action", "tool", "llm", "parser", "subagent"]
)
def test_shorthand_matches_generic_scope_and_wildcard(category: str) -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    for name in ("named", "com.foo.*", "*"):
        config = AgentConfiguration({option.get_key(): [{"scope": {category: name}}]})
        expected = TraceLogTarget.entity(category, None if name == "*" else name)
        assert config.get(option) == [expected]
        generic = AgentConfiguration()
        generic.set(option, [expected])
        assert config.get_conf_data() == generic.get_conf_data()


def test_trace_log_targets_preserve_omitted_level_and_custom_categories() -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    config = AgentConfiguration()
    config.set(
        option,
        [
            TraceLogTarget.all(TraceLogLevel.OFF),
            TraceLogTarget.events(),
            TraceLogTarget.entity("embedding", "search", TraceLogLevel.VERBOSE),
        ],
    )
    assert config.get_conf_data()[option.get_key()] == [
        {"scope": "ALL", "level": "OFF"},
        {"scope": "EVENT_ONLY"},
        {
            "scope": {"entityType": "embedding", "entityName": "search"},
            "level": "VERBOSE",
        },
    ]
    config.get(option).clear()
    assert len(config.get(option)) == 3


def test_trace_log_maps_accept_enums_case_insensitive_presets_and_null_level() -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    config = AgentConfiguration(
        {
            option.get_key(): [
                {"scope": TraceLogScope.ALL, "level": TraceLogLevel.VERBOSE},
                {"scope": "event_only", "level": "standard"},
                {"scope": {"entityType": "tool", "entityName": None}, "level": None},
            ]
        }
    )
    assert config.get(option) == [
        TraceLogTarget.all(TraceLogLevel.VERBOSE),
        TraceLogTarget.events(TraceLogLevel.STANDARD),
        TraceLogTarget.tools(),
    ]
    assert config.get_conf_data()[option.get_key()] == [
        {"scope": "ALL", "level": "VERBOSE"},
        {"scope": "EVENT_ONLY", "level": "STANDARD"},
        {"scope": {"entityType": "tool"}},
    ]


def test_trace_log_duplicate_aliases_preserve_levels_for_runtime_resolution() -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    config = AgentConfiguration()
    config.set(
        option,
        [
            TraceLogTarget.events(),
            {"scope": {"event": "*"}, "level": "OFF"},
            {"scope": {"entityType": "event"}, "level": "STANDARD"},
        ],
    )
    assert config.get_conf_data()[option.get_key()] == [
        {"scope": "EVENT_ONLY"},
        {"scope": "EVENT_ONLY", "level": "OFF"},
        {"scope": "EVENT_ONLY", "level": "STANDARD"},
    ]


@pytest.mark.parametrize(
    "targets",
    [
        None,
        {},
        "ALL",
        [None],
        [{}],
        [{"scope": "ALL", "unknown": True}],
        [{"scope": "ALL", "level": "DEBUG"}],
        [{"scope": {"event": "created", "tool": "search"}}],
        [{"scope": {"event": "created", "entityType": "event"}}],
        [{"scope": {"event": None}}],
        [{"scope": {"event": " "}}],
        [{"scope": {"event": 1}}],
        [{"scope": {"event": "com.*.foo"}}],
        [{"scope": {"embedding": "search"}}],
        [{"scope": {"entityType": "event", "entityName": "*"}}],
    ],
)
def test_trace_log_targets_reject_malformed_configuration(targets) -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    with pytest.raises(ValueError, match="Invalid trace-log.targets"):
        AgentConfiguration().set(option, targets)
    with pytest.raises(ValueError, match="Invalid trace-log.targets"):
        AgentConfiguration({option.get_key(): targets})
    config = AgentConfiguration()
    config.conf_data[option.get_key()] = targets
    with pytest.raises(ValueError, match="Invalid trace-log.targets"):
        config.get(option)


def test_trace_log_typed_constructor_input_is_json_native() -> None:
    option = AgentConfigOptions.TRACE_LOG_TARGETS
    config = AgentConfiguration({option.get_key(): [TraceLogTarget.tool("search")]})
    restored = AgentConfiguration.model_validate_json(config.model_dump_json())
    assert restored.get(option) == [TraceLogTarget.tool("search")]
    assert restored.get_conf_data()[option.get_key()] == [
        {"scope": {"entityType": "tool", "entityName": "search"}}
    ]


def test_trace_log_target_error_identifies_list_entry() -> None:
    with pytest.raises(ValueError, match="trace-log.targets: target 1: unknown field"):
        AgentConfiguration().set(
            AgentConfigOptions.TRACE_LOG_TARGETS,
            [TraceLogTarget.all(), {"scope": {"unknown": "search"}}],
        )
