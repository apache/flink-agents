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

from pyflink.java_gateway import get_gateway
from pyflink.util.java_utils import add_jars_to_context_class_loader

from flink_agents.api.configuration import ConfigOption
from flink_agents.api.core_options import AgentConfigOptions, AgentExecutionOptions

_JAVA_PRIMITIVE_TYPE_TO_PYTHON: dict[str, type] = {
    "java.lang.String": str,
    "java.lang.Integer": int,
    "java.lang.Long": int,
    "java.lang.Boolean": bool,
    "java.lang.Float": float,
    "java.lang.Double": float,
    "java.util.List": list,
}


def collect_python_config_options(python_options_class: type) -> dict[str, ConfigOption]:
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


def normalize_java_default(java_default: Any, python_config_type: type) -> Any:
    """Convert a Java default value into a Python-comparable form."""
    if java_default is None:
        return None

    if hasattr(java_default, "name") and callable(java_default.name):
        enum_name = java_default.name()
        if isinstance(python_config_type, type) and issubclass(python_config_type, Enum):
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
    java_default = normalize_java_default(
        java_option.getDefaultValue(),
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

    assert_options_class_matches_java(
        AgentConfigOptions, java_agent_config_options, jvm
    )
    assert_options_class_matches_java(
        AgentExecutionOptions, java_agent_execution_options, jvm
    )


if __name__ == "__main__":
    main()
