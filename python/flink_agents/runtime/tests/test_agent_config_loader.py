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

from flink_agents.api.core_options import AgentConfigOptions, LoggerType
from flink_agents.api.trace_log import TraceLogLevel, TraceLogTarget
from flink_agents.api.version_compatibility import flink_version_manager
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime import agent_config_loader
from flink_agents.runtime.agent_config_loader import (
    CONFIG_FILE_NAME,
    LEGACY_CONFIG_FILE_NAME,
    _load_yaml_config,
    _parse_legacy_flink_conf,
    is_flink_2_or_later,
    load_agent_configuration,
)

FLINK_1_20 = "1.20.3"
FLINK_2_3 = "2.3.0"


def _use_flink(monkeypatch: pytest.MonkeyPatch, flink_version: str | None) -> None:
    monkeypatch.setattr(flink_version_manager, "_flink_version", flink_version)
    monkeypatch.setattr(flink_version_manager, "_initialized", True)


def _write(directory: Path, file_name: str, *lines: str) -> None:
    (directory / file_name).write_text("\n".join(lines) + "\n", encoding="utf-8")


def _write_legacy_conf(directory: Path, *lines: str) -> None:
    _write(directory, LEGACY_CONFIG_FILE_NAME, *lines)


def _write_config_yaml(directory: Path, *lines: str) -> None:
    _write(directory, CONFIG_FILE_NAME, *lines)


@pytest.fixture
def flink_1(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(agent_config_loader, "is_flink_2_or_later", lambda: False)


@pytest.fixture
def flink_2(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(agent_config_loader, "is_flink_2_or_later", lambda: True)


@pytest.mark.parametrize(
    ("flink_version", "expected"),
    [
        (FLINK_1_20, False),
        ("1.20-vvr-11.5.0", False),
        # PEP 440 local versions normalise to 1.0.0, still below 2.0.
        ("1.20+vvr.11.0.dev0", False),
        (FLINK_2_3, True),
        ("2.0-SNAPSHOT", True),
    ],
)
def test_is_flink_2_or_later(monkeypatch, flink_version, expected):
    _use_flink(monkeypatch, flink_version)

    assert is_flink_2_or_later() is expected


@pytest.mark.parametrize("flink_version", [None, "", "<unknown>"])
def test_unknown_version_counts_as_flink_1(monkeypatch, flink_version):
    _use_flink(monkeypatch, flink_version)

    assert is_flink_2_or_later() is False


def test_no_config_dir_loads_nothing():
    assert load_agent_configuration(None).get_conf_data() == {}


def test_flink_1_loads_legacy_file_when_only_legacy_file_exists(tmp_path, flink_1):
    _write_legacy_conf(tmp_path, "agent.key1: fromLegacy")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "fromLegacy"
    }


def test_flink_1_loads_config_yaml_when_only_config_yaml_exists(tmp_path, flink_1):
    _write_config_yaml(tmp_path, "agent:", "    key1: fromConfigYaml")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "fromConfigYaml"
    }


def test_flink_1_prefers_legacy_file_over_config_yaml(tmp_path, flink_1):
    _write_config_yaml(tmp_path, "agent:", "    key1: fromConfigYaml")
    _write_legacy_conf(tmp_path, "agent.key1: fromLegacy")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "fromLegacy"
    }


def test_flink_1_fails_when_no_config_file_exists(tmp_path, flink_1):
    with pytest.raises(RuntimeError) as exc_info:
        load_agent_configuration(str(tmp_path))

    assert str(tmp_path) in str(exc_info.value)


def test_flink_2_ignores_legacy_file(tmp_path, flink_2, caplog):
    _write_legacy_conf(tmp_path, "agent.key1: fromLegacy")

    with pytest.raises(RuntimeError) as exc_info:
        load_agent_configuration(str(tmp_path))

    assert str(tmp_path) in str(exc_info.value)
    assert "reads only config.yaml" in caplog.text


def test_flink_2_loads_config_yaml_when_only_config_yaml_exists(tmp_path, flink_2):
    _write_config_yaml(tmp_path, "agent:", "    key1: fromConfigYaml")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "fromConfigYaml"
    }


def test_flink_2_loads_config_yaml_when_both_files_exist(tmp_path, flink_2):
    _write_config_yaml(tmp_path, "agent:", "    key1: fromConfigYaml")
    _write_legacy_conf(tmp_path, "agent.key1: fromLegacy")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "fromConfigYaml"
    }


def test_flink_2_fails_when_no_config_file_exists(tmp_path, flink_2):
    with pytest.raises(RuntimeError) as exc_info:
        load_agent_configuration(str(tmp_path))

    assert str(tmp_path) in str(exc_info.value)


def test_legacy_file_accepts_duplicate_keys_and_values_containing_colon_space(
    tmp_path, flink_1
):
    # Both lines are rejected by a standard YAML parser but accepted by Flink 1.20.
    _write_legacy_conf(
        tmp_path,
        "env.java.opts.all: -Dfoo=bar",
        "env.java.opts.all: -Dfoo=baz",
        "kubernetes.jobmanager.annotations: owner: team-a",
        "agent.key1: first",
        "agent.key1: second",
        "agent.key2: a: b",
    )

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "second",
        "key2": "a: b",
    }


def test_legacy_file_without_agent_options(tmp_path, flink_1):
    _write_legacy_conf(
        tmp_path,
        "# Flink configuration as shipped by a distribution",
        "jobmanager.rpc.address: localhost",
        "taskmanager.memory.process.size: 1728m",
        "env.java.opts.all: -XX:+UseG1GC -Dfoo=bar",
    )

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {}


def test_legacy_file_ignores_nested_agent_block(tmp_path, flink_1):
    _write_legacy_conf(tmp_path, "agent:", "    key1: nested", "agent.key2: flat")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {"key2": "flat"}


def test_legacy_file_values_are_strings_and_convert_on_read(tmp_path, flink_1):
    _write_legacy_conf(
        tmp_path,
        "agent.trace-log.base-dir: /tmp/agent-traces",
        "agent.trace-log.pretty-print: true",
    )

    conf = load_agent_configuration(str(tmp_path))

    assert conf.get_conf_data() == {
        "trace-log.base-dir": "/tmp/agent-traces",
        "trace-log.pretty-print": "true",
    }
    assert conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR) == (
        "/tmp/agent-traces"
    )
    assert conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT) is True


def test_legacy_false_string_is_not_truthy(tmp_path, flink_1):
    _write_legacy_conf(tmp_path, "agent.trace-log.pretty-print: false")

    conf = load_agent_configuration(str(tmp_path))

    assert conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT) is False


def test_config_yaml_accepts_nested_and_flattened_keys(tmp_path, flink_2):
    _write_config_yaml(tmp_path, "agent:", "    key1: v1", "agent.key2: v2")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1": "v1",
        "key2": "v2",
    }


@pytest.mark.parametrize("flink_version", [FLINK_1_20, FLINK_2_3])
@pytest.mark.parametrize("flat_first", [True, False])
@pytest.mark.parametrize(
    "flat",
    [
        "agent.trace-log.pretty-print: false\n",
        "agent.trace-log:\n  pretty-print: false\n",
    ],
)
def test_config_yaml_uses_file_order(
    tmp_path, monkeypatch, flink_version, flat_first, flat
):
    _use_flink(monkeypatch, flink_version)
    nested = (
        "agent:\n"
        "  job-identifier: demo\n"
        "  max-retries: 3\n"
        "  trace-log:\n"
        "    pretty-print: true\n"
    )
    _write_config_yaml(tmp_path, flat + nested if flat_first else nested + flat)

    conf = load_agent_configuration(str(tmp_path))

    assert conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT) is flat_first
    assert conf.get_str("job-identifier") == "demo"
    assert conf.get_int("max-retries") == 3


@pytest.mark.parametrize("flink_version", [FLINK_1_20, FLINK_2_3])
@pytest.mark.parametrize("flat_first", [True, False])
def test_config_yaml_uses_file_order_within_agent_section(
    tmp_path, monkeypatch, flink_version, flat_first
):
    _use_flink(monkeypatch, flink_version)
    flat = "  trace-log.pretty-print: false\n"
    nested = "  trace-log:\n    pretty-print: true\n"
    _write_config_yaml(
        tmp_path, "agent:\n" + (flat + nested if flat_first else nested + flat)
    )

    conf = load_agent_configuration(str(tmp_path))

    assert conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT) is flat_first


def test_config_yaml_nested_mapping_is_flattened(tmp_path, flink_2):
    _write_config_yaml(
        tmp_path,
        "agent:",
        "    key1:",
        "        key2: v1",
        "        key3: 'v2'",
        "    key4: 1",
        "    key6: 1.5",
        "    key7: true",
    )

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {
        "key1.key2": "v1",
        "key1.key3": "v2",
        "key4": 1,
        "key6": 1.5,
        "key7": True,
    }


def test_config_yaml_without_agent_section(tmp_path, flink_2):
    _write_config_yaml(
        tmp_path, "jobmanager:", "    rpc:", "        address: localhost"
    )

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {}


def test_fail_if_config_yaml_unparseable(tmp_path, flink_2):
    _write_config_yaml(tmp_path, "agent.key1: [unclosed")

    with pytest.raises(RuntimeError) as exc_info:
        load_agent_configuration(str(tmp_path))

    assert str(tmp_path) in str(exc_info.value)


def _parse_legacy_conf(tmp_path: Path, *lines: str) -> dict[str, str]:
    conf_file = tmp_path / "flink-conf.yaml"
    conf_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return _parse_legacy_flink_conf(conf_file)


def test_duplicate_key_overrides_earlier_value(tmp_path):
    assert _parse_legacy_conf(tmp_path, "key: first", "key: second") == {"key": "second"}


def test_value_containing_colon_space_is_kept_whole(tmp_path):
    assert _parse_legacy_conf(tmp_path, "key: a: b: c") == {"key": "a: b: c"}


def test_comments_are_dropped(tmp_path):
    conf = _parse_legacy_conf(
        tmp_path,
        "# full-line comment",
        "key: value # trailing comment",
        "other: a#b",
    )

    assert conf == {"key": "value", "other": "a"}


def test_lines_without_key_value_pair_are_skipped(tmp_path, caplog):
    conf = _parse_legacy_conf(tmp_path, "key:value", "nested:", "key-only", "valid: yes")

    assert conf == {"valid": "yes"}
    assert "missing space after ':'" in caplog.text


def test_empty_key_or_value_is_skipped(tmp_path, caplog):
    conf = _parse_legacy_conf(
        tmp_path, ": value", "key: ", "key2:   # only a comment", "valid: yes"
    )

    assert conf == {"valid": "yes"}
    assert "Key or value was empty" in caplog.text


def test_keys_and_values_are_trimmed_and_kept_as_strings(tmp_path):
    conf = _parse_legacy_conf(
        tmp_path,
        "  key   :   1  ",
        "flag: true",
        "quoted: 'v'",
        "list: [a, b]",
    )

    assert conf == {
        "key": "1",
        "flag": "true",
        "quoted": "'v'",
        "list": "[a, b]",
    }


def test_indented_lines_are_read_as_top_level_keys(tmp_path):
    assert _parse_legacy_conf(tmp_path, "agent:", "    key1: v1") == {"key1": "v1"}


def test_missing_file_fails(tmp_path):
    with pytest.raises(FileNotFoundError):
        _parse_legacy_flink_conf(tmp_path / "missing.yaml")


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
        config = _load_yaml_config(Path(config_file))

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


def test_load_configuration_with_invalid_file() -> None:
    """Test loading configuration with a non-existent file."""
    with pytest.raises(FileNotFoundError):
        _load_yaml_config(Path("/path/to/nonexistent/file.yaml"))


def test_load_configuration_with_invalid_yaml() -> None:
    """Test loading configuration with invalid YAML content."""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        f.write("invalid: yaml: content: [")
        config_file = f.name

    try:
        with pytest.raises(yaml.YAMLError):
            _load_yaml_config(Path(config_file))
    finally:
        config_file = Path(config_file)
        config_file.unlink()


def test_load_configuration_from_flat_agent_keys(tmp_path: Path) -> None:
    """Flat agent.* keys are read and stripped of their prefix."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text(
        "parallelism.default: 2\n"
        "agent.job-identifier: my-job\n"
        "agent.tool-call.async: false\n"
        "agent.trace-log.base-dir: /tmp/agent-logs\n"
    )

    config = _load_yaml_config(config_file)

    assert config.get_conf_data() == {
        "job-identifier": "my-job",
        "tool-call.async": False,
        "trace-log.base-dir": "/tmp/agent-logs",
    }


@pytest.mark.parametrize("flat_first", [True, False])
def test_agent_keys_follow_file_order(tmp_path: Path, flat_first: bool) -> None:
    """The later declaration wins regardless of whether it is nested or flat."""
    config_file = tmp_path / "config.yaml"
    nested = "agent:\n  job-identifier: nested\n  debug: true\n"
    flat = "agent.job-identifier: flat\n"
    config_file.write_text(flat + nested if flat_first else nested + flat)

    config = _load_yaml_config(config_file)

    assert config.get_str("job-identifier") == ("nested" if flat_first else "flat")
    assert config.get_bool("debug") is True


def test_load_empty_configuration_file(tmp_path: Path) -> None:
    """An empty configuration file contributes no agent settings."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text("")

    config = _load_yaml_config(config_file)

    assert config.get_conf_data() == {}


def test_load_configuration_with_empty_agent_section(tmp_path: Path) -> None:
    """An agent section without entries contributes no agent settings."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text("agent:\n")

    config = _load_yaml_config(config_file)

    assert config.get_conf_data() == {}


def test_load_configuration_rejects_non_mapping_document(tmp_path: Path) -> None:
    """A configuration file must contain a YAML mapping at the top level."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text("- job-identifier\n")

    with pytest.raises(TypeError, match="expected a YAML mapping at the top level"):
        _load_yaml_config(config_file)


def test_load_configuration_rejects_non_mapping_agent_section(tmp_path: Path) -> None:
    """The nested agent section must be a YAML mapping."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text("agent: not-a-mapping\n")

    with pytest.raises(TypeError, match="'agent' must be a mapping"):
        _load_yaml_config(config_file)


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("FILE", LoggerType.FILE),
        ("file", LoggerType.FILE),
        ("File", LoggerType.FILE),
        ("SLF4J", LoggerType.SLF4J),
        ("slf4j", LoggerType.SLF4J),
        ("Slf4j", LoggerType.SLF4J),
    ],
)
def test_trace_log_output_type_from_yaml(
    tmp_path: Path, value: str, expected: LoggerType
) -> None:
    config_file = tmp_path / "agent.yaml"
    config_file.write_text(
        yaml.safe_dump({"agent": {"trace-log": {"output-type": value}}}),
        encoding="utf-8",
    )
    config = _load_yaml_config(config_file)
    assert config.get(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE) is expected

    config.set(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE, expected)
    serialized = config.model_dump_json()
    assert json.loads(serialized)["conf_data"]["trace-log.output-type"] == value.lower()
    restored = AgentConfiguration.model_validate_json(serialized)
    assert restored.get(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE) is expected


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
    config = _load_yaml_config(config_file)
    assert config.get(AgentConfigOptions.TRACE_LOG_TARGETS) == expected

    typed_config = AgentConfiguration()
    typed_config.set(AgentConfigOptions.TRACE_LOG_TARGETS, expected)
    assert config.get_conf_data() == typed_config.get_conf_data()
    plan = AgentPlan(actions={}, config=config)
    serialized = plan.model_dump_json(serialize_as_any=True)
    restored = AgentPlan.model_validate_json(serialized)
    assert restored.config.get(AgentConfigOptions.TRACE_LOG_TARGETS) == expected
    assert json.loads(serialized)["config"]["conf_data"] == config.get_conf_data()
