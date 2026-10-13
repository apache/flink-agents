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
"""Client-side Java/Python ConfigOption parity check.

Loads the Java API JAR into a PyFlink gateway process and compares each
explicitly declared Python ``ConfigOption`` against the Java definition.
Invoked by ``test_java_config_in_python.sh`` (not inside the Pemja worker), so
using ``get_gateway()`` here is intentional and separate from runtime import
safety.
"""

from enum import Enum
from pathlib import Path
from typing import Any

from py4j.java_collections import JavaList, JavaMap
from py4j.java_gateway import JavaObject
from py4j.protocol import Py4JJavaError
from pyflink.java_gateway import get_gateway
from pyflink.util.java_utils import add_jars_to_context_class_loader

from flink_agents.api.configuration import ConfigOption
from flink_agents.api.core_options import (
    AgentConfigOptions,
    AgentExecutionOptions,
    MemoryEventOptions,
    TraceLogLevel,
    TraceLogScope,
)
from flink_agents.api.trace_log import TraceLogTarget, _serialize_targets

_JAVA_PRIMITIVE_TYPE_TO_PYTHON: dict[str, type] = {
    "java.lang.String": str,
    "java.lang.Integer": int,
    "java.lang.Long": int,
    "java.lang.Boolean": bool,
    "java.lang.Float": float,
    "java.lang.Double": float,
    "java.util.List": list,
}


def collect_python_config_options(
    python_options_class: type,
) -> dict[str, ConfigOption]:
    """Return ``{FIELD_NAME: ConfigOption}`` declared on a Python options class."""
    return {
        name: value
        for name, value in vars(python_options_class).items()
        if not name.startswith("_") and isinstance(value, ConfigOption)
    }


def collect_java_config_options(java_options_class: Any, jvm: Any) -> dict[str, Any]:
    """Return ``{FIELD_NAME: ConfigOption}`` from Java static fields via reflection."""
    modifier = jvm.java.lang.reflect.Modifier
    class_loader = jvm.java.lang.Thread.currentThread().getContextClassLoader()
    java_config_option_class = class_loader.loadClass(
        "org.apache.flink.agents.api.configuration.ConfigOption"
    )
    options: dict[str, Any] = {}
    for field in java_options_class.getDeclaredFields():
        if not modifier.isStatic(field.getModifiers()):
            continue
        if not java_config_option_class.isAssignableFrom(field.getType()):
            continue
        field.setAccessible(True)
        options[field.getName()] = field.get(None)
    return options


def _java_type_matches_python(java_type_name: str, python_config_type: type) -> bool:
    expected_primitive = _JAVA_PRIMITIVE_TYPE_TO_PYTHON.get(java_type_name)
    java_simple_name = java_type_name.rsplit(".", maxsplit=1)[-1]
    if "$" in java_simple_name:
        java_simple_name = java_simple_name.split("$", maxsplit=1)[-1]

    if expected_primitive is not None:
        return python_config_type is expected_primitive

    return python_config_type.__name__ == java_simple_name


def _normalize_java_collection(value: Any) -> Any:
    if isinstance(value, JavaMap):
        return {key: _normalize_java_collection(item) for key, item in value.items()}
    if isinstance(value, JavaList):
        return [_normalize_java_collection(item) for item in value]
    if (
        isinstance(value, JavaObject)
        and value.getClass().getName()
        == "org.apache.flink.agents.api.logger.TraceLogTarget"
    ):
        return _normalize_java_collection(value.toConfig())
    return value


def normalize_java_default(
    java_default: Any, java_type_name: str, python_config_type: type
) -> Any:
    """Convert a Java default value into a Python-comparable form."""
    if java_default is None:
        return None

    if python_config_type is list:
        return [_normalize_java_collection(item) for item in java_default]

    if hasattr(java_default, "name") and callable(java_default.name):
        enum_name = java_default.name()
        if isinstance(python_config_type, type) and issubclass(
            python_config_type, Enum
        ):
            return python_config_type[enum_name]
        return enum_name

    if python_config_type is int and isinstance(java_default, int | float):
        return int(java_default)

    return java_default


def assert_python_option_matches_java(
    option_name: str,
    python_option: ConfigOption,
    java_option: Any,
) -> None:
    """Assert key, type, and default parity for one option pair."""
    python_config_type = python_option.get_type()
    java_type_name = java_option.getTypeName()

    assert python_option.get_key() == java_option.getKey(), (
        f"{option_name}: key mismatch "
        f"(python={python_option.get_key()!r}, java={java_option.getKey()!r})"
    )
    assert _java_type_matches_python(java_type_name, python_config_type), (
        f"{option_name}: type mismatch "
        f"(python={python_config_type!r}, java={java_type_name!r})"
    )

    python_default = python_option.get_default_value()
    if python_option.get_key() == AgentConfigOptions.TRACE_LOG_TARGETS.get_key():
        python_default = _serialize_targets(python_default)
    java_default = normalize_java_default(
        java_option.getDefaultValue(),
        java_type_name,
        python_config_type,
    )
    assert python_default == java_default, (
        f"{option_name}: default mismatch "
        f"(python={python_default!r}, java={java_default!r})"
    )


def assert_options_class_matches_java(
    python_options_class: type,
    java_options_class: Any,
    jvm: Any,
) -> None:
    """Compare Python and Java ConfigOption declarations in both directions."""
    python_options = collect_python_config_options(python_options_class)
    java_options = collect_java_config_options(java_options_class, jvm)

    python_names = set(python_options)
    java_names = set(java_options)
    assert python_names == java_names, (
        f"{python_options_class.__name__}: option field name mismatch "
        f"(python-only={sorted(python_names - java_names)!r}, "
        f"java-only={sorted(java_names - python_names)!r})"
    )

    for option_name in sorted(python_names):
        assert_python_option_matches_java(
            option_name,
            python_options[option_name],
            java_options[option_name],
        )


def assert_enum_matches_java(python_enum: type[Enum], java_enum_class: Any) -> None:
    """Compare enum names used inside trace log targets."""
    java_names = {value.name() for value in java_enum_class.getEnumConstants()}
    assert set(python_enum.__members__) == java_names, (
        f"{python_enum.__name__}: enum values differ "
        f"(python={sorted(python_enum.__members__)!r}, java={sorted(java_names)!r})"
    )


def assert_trace_log_target_factories_match_java(jvm: Any) -> None:
    """Compare canonical wire values from typed factories across both languages."""
    java_target = jvm.org.apache.flink.agents.api.logger.TraceLogTarget
    java_level = jvm.org.apache.flink.agents.api.logger.TraceLogLevel
    factories = (
        ("all", None),
        ("events", "event"),
        ("actions", "action"),
        ("tools", "tool"),
        ("llms", "llm"),
        ("parsers", "parser"),
        ("subagents", "subagent"),
    )
    for plural, singular in factories:
        for level in (None, *TraceLogLevel):
            python_args = [] if level is None else [level]
            java_args = [] if level is None else [getattr(java_level, level.name)]
            python_target = getattr(TraceLogTarget, plural)(*python_args)
            target = getattr(java_target, plural)(*java_args)
            assert _serialize_targets([python_target])[0] == _normalize_java_collection(
                target
            ), (plural, level)
            if singular is not None:
                for name in ("named", "com.example.*"):
                    python_target = getattr(TraceLogTarget, singular)(
                        name, *python_args
                    )
                    target = getattr(java_target, singular)(name, *java_args)
                    assert _serialize_targets([python_target])[0] == (
                        _normalize_java_collection(target)
                    ), (singular, name, level)
    for name in (None, "search"):
        python_target = TraceLogTarget.entity("embedding", name, TraceLogLevel.VERBOSE)
        target = java_target.entity("embedding", name, java_level.VERBOSE)
        assert _serialize_targets([python_target])[0] == _normalize_java_collection(
            target
        )


def _java_config_value(value: Any, jvm: Any) -> Any:
    if isinstance(value, dict):
        result = jvm.java.util.LinkedHashMap()
        for key, item in value.items():
            result.put(key, _java_config_value(item, jvm))
        return result
    if isinstance(value, list):
        result = jvm.java.util.ArrayList()
        for item in value:
            result.add(_java_config_value(item, jvm))
        return result
    return value


def assert_trace_log_target_validation_matches_java(jvm: Any) -> None:
    """Run raw configuration examples through both shared target codecs."""
    java_target = jvm.org.apache.flink.agents.api.logger.TraceLogTarget
    examples = [
        [],
        [{"scope": "all", "level": "verbose"}],
        [{"scope": "EVENT_ONLY", "level": None}],
        [{"scope": {"entityType": "embedding", "entityName": "search"}}],
        [{"scope": {"entityType": "tool", "entityName": None}}],
        [
            {"scope": "EVENT_ONLY"},
            {"scope": {"entityType": "event"}, "level": "OFF"},
            {"scope": {"event": "*"}, "level": "STANDARD"},
        ],
        None,
        {},
        "ALL",
        [None],
        [{}],
        [{"scope": "ALL", "unknown": True}],
        [{"scope": "ALL", "level": "DEBUG"}],
        [{"scope": "ALL", "level": 1}],
        [{"scope": {"event": "created", "tool": "search"}}],
        [{"scope": {"event": "created", "entityType": "event"}}],
        [{"scope": {"event": None}}],
        [{"scope": {"event": " "}}],
        [{"scope": {"event": 1}}],
        [{"scope": {"event": "com.*.foo"}}],
        [{"scope": {"embedding": "search"}}],
        [{"scope": {"entityType": "event", "entityName": "*"}}],
    ]
    for category in ("event", "action", "tool", "llm", "parser", "subagent"):
        examples.extend(
            [{"scope": {category: name}}] for name in ("named", "com.example.*", "*")
        )
    for raw in examples:
        python_result = None
        python_error = False
        try:
            python_result = _serialize_targets(raw)
        except ValueError:
            python_error = True
        java_result = None
        java_error = False
        try:
            java_result = _normalize_java_collection(
                java_target.parseTargets(_java_config_value(raw, jvm))
            )
        except Py4JJavaError as error:
            if (
                error.java_exception.getClass().getName()
                != "java.lang.IllegalArgumentException"
            ):
                raise
            java_error = True
        assert python_error == java_error, (raw, python_error, java_error)
        if not python_error:
            assert python_result == java_result, (raw, python_result, java_result)


def main() -> None:
    current_dir = Path(__file__).parent

    jars = Path(current_dir).glob("../../../../../api/target/flink-agents-api-*.jar")
    jar_urls = [f"file:///{jar.resolve()}" for jar in jars]
    add_jars_to_context_class_loader(jar_urls)

    jvm = get_gateway().jvm
    class_loader = jvm.java.lang.Thread.currentThread().getContextClassLoader()
    java_agent_config_options = class_loader.loadClass(
        "org.apache.flink.agents.api.configuration.AgentConfigOptions"
    )
    java_agent_execution_options = class_loader.loadClass(
        "org.apache.flink.agents.api.agents.AgentExecutionOptions"
    )
    java_memory_event_options = class_loader.loadClass(
        "org.apache.flink.agents.api.configuration.MemoryEventOptions"
    )

    assert_options_class_matches_java(
        AgentConfigOptions, java_agent_config_options, jvm
    )
    assert_options_class_matches_java(
        AgentExecutionOptions, java_agent_execution_options, jvm
    )
    assert_options_class_matches_java(
        MemoryEventOptions, java_memory_event_options, jvm
    )
    assert_trace_log_target_factories_match_java(jvm)
    assert_trace_log_target_validation_matches_java(jvm)
    for python_enum in (TraceLogLevel, TraceLogScope):
        assert_enum_matches_java(
            python_enum,
            class_loader.loadClass(
                f"org.apache.flink.agents.api.logger.{python_enum.__name__}"
            ),
        )


if __name__ == "__main__":
    main()
