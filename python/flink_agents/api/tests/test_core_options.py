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
import ast
import importlib
import sys
import types
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from flink_agents.api.configuration import ConfigOption


def _clear_modules(prefixes: tuple[str, ...]) -> None:
    for name in list(sys.modules):
        if any(name == prefix or name.startswith(f"{prefix}.") for prefix in prefixes):
            sys.modules.pop(name, None)


def test_core_options_module_does_not_import_pyflink() -> None:
    path = Path(__file__).resolve().parents[1] / "core_options.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))

    imported_modules: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported_modules.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module is not None:
            imported_modules.append(node.module)

    assert "pyflink" not in imported_modules
    assert "pyflink.java_gateway" not in imported_modules


def test_import_core_options_does_not_call_get_gateway() -> None:
    _clear_modules(("flink_agents.api.core_options",))

    fake_gateway_module = types.ModuleType("pyflink.java_gateway")
    fake_gateway_module.get_gateway = MagicMock()

    fake_pyflink_module = types.ModuleType("pyflink")
    fake_pyflink_module.java_gateway = fake_gateway_module

    with pytest.MonkeyPatch.context() as monkeypatch:
        monkeypatch.setitem(sys.modules, "pyflink", fake_pyflink_module)
        monkeypatch.setitem(
            sys.modules, "pyflink.java_gateway", fake_gateway_module
        )

        importlib.import_module("flink_agents.api.core_options")

        fake_gateway_module.get_gateway.assert_not_called()


def test_runtime_import_chain_does_not_call_get_gateway() -> None:
    """Guard the Pemja import path that previously failed in TaskManager workers."""
    pytest.importorskip("cloudpickle")
    pytest.importorskip("pyflink")

    # Only evict the modules under test. Clearing entire package trees (e.g.
    # ``flink_agents.plan``) removes already-collected test modules from
    # ``sys.modules`` and breaks ``inspect.getmodule()`` for functions defined
    # in those tests.
    _clear_modules(
        (
            "flink_agents.api.core_options",
            "flink_agents.runtime.flink_runner_context",
        )
    )

    with patch("pyflink.java_gateway.get_gateway") as get_gateway:
        importlib.import_module("flink_agents.runtime.flink_runner_context")
        get_gateway.assert_not_called()


def _collect_config_options(options_class: type) -> dict[str, ConfigOption]:
    return {
        name: value
        for name, value in vars(options_class).items()
        if not name.startswith("_") and isinstance(value, ConfigOption)
    }


def test_agent_config_options_are_explicitly_declared() -> None:
    from flink_agents.api.core_options import AgentConfigOptions, EventLogLevel

    options = _collect_config_options(AgentConfigOptions)
    assert len(options) == 23
    assert options["BASE_LOG_DIR"].get_key() == "baseLogDir"
    assert options["KAFKA_BOOTSTRAP_SERVERS"].get_default_value() == "localhost:9092"
    assert options["EVENT_LOG_LEVEL"].get_default_value() is EventLogLevel.STANDARD


def test_unknown_agent_config_option_raises_attribute_error() -> None:
    from flink_agents.api.core_options import AgentConfigOptions

    with pytest.raises(AttributeError):
        _ = AgentConfigOptions.UNKNOWN_OPTION
