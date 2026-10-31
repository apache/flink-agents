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
from pathlib import Path

import pytest

from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.version_compatibility import flink_version_manager
from flink_agents.runtime import agent_config_loader
from flink_agents.runtime.agent_config_loader import (
    CONFIG_FILE_NAME,
    LEGACY_CONFIG_FILE_NAME,
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


def test_config_yaml_flattened_keys_override_nested_keys(tmp_path, flink_2):
    _write_config_yaml(tmp_path, "agent:", "    key1: nested", "agent.key1: flat")

    assert load_agent_configuration(str(tmp_path)).get_conf_data() == {"key1": "flat"}


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
