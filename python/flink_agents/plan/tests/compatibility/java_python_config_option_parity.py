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
"""Helpers to compare Python ConfigOption declarations against Java ConfigOption."""

from enum import Enum
from typing import Any

from flink_agents.api.configuration import ConfigOption

_JAVA_PRIMITIVE_TYPE_TO_PYTHON: dict[str, type] = {
    "java.lang.String": str,
    "java.lang.Integer": int,
    "java.lang.Long": int,
    "java.lang.Boolean": bool,
    "java.lang.Float": float,
    "java.lang.Double": float,
    "java.util.List": list,
}


def collect_config_options(options_class: type) -> dict[str, ConfigOption]:
    """Return ``{FIELD_NAME: ConfigOption}`` declared on a Python options class."""
    return {
        name: value
        for name, value in vars(options_class).items()
        if not name.startswith("_") and isinstance(value, ConfigOption)
    }


def _java_type_matches_python(java_type_name: str, python_config_type: type) -> bool:
    expected_primitive = _JAVA_PRIMITIVE_TYPE_TO_PYTHON.get(java_type_name)
    java_simple_name = java_type_name.rsplit(".", maxsplit=1)[-1]
    if "$" in java_simple_name:
        java_simple_name = java_simple_name.split("$", maxsplit=1)[-1]

    if expected_primitive is not None:
        return python_config_type is expected_primitive

    if isinstance(python_config_type, type) and issubclass(python_config_type, Enum):
        return python_config_type.__name__ == java_simple_name

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
) -> None:
    """Compare every Python ConfigOption on a class with the Java static field."""
    python_options = collect_config_options(python_options_class)
    for option_name, python_option in sorted(python_options.items()):
        java_option = getattr(java_options_class, option_name)
        assert_python_option_matches_java(option_name, python_option, java_option)
