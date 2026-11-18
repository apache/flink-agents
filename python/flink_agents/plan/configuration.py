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
from datetime import timedelta
from typing import Any, Dict, NoReturn, Type

from pydantic import BaseModel
from typing_extensions import override

from flink_agents.api.configuration import (
    ConfigOption,
    Configuration,
)
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.duration import (
    format_duration,
    is_positive,
    parse_duration,
    require_whole_milliseconds,
)
from flink_agents.api.trace_log import _parse_targets, _serialize_targets

_TRACE_LOG_TARGETS_KEY = "trace-log.targets"


def _normalize_conf_data(conf_data: Dict[str, Any]) -> Dict[str, Any]:
    normalized = {
        key: format_duration(value) if isinstance(value, timedelta) else value
        for key, value in conf_data.items()
    }
    if _TRACE_LOG_TARGETS_KEY not in normalized:
        return normalized
    return {
        **normalized,
        _TRACE_LOG_TARGETS_KEY: _serialize_targets(normalized[_TRACE_LOG_TARGETS_KEY]),
    }


class AgentConfiguration(BaseModel, Configuration):
    """Configuration entries keyed by their full option names.

    Map and list values remain option values. YAML key expansion belongs to the
    configuration loader.
    """

    conf_data: Dict[str, Any]

    def __init__(self, conf_data: Dict[str, Any] | None = None) -> None:
        """Initialize with optional configuration data."""
        if conf_data is None:
            super().__init__(conf_data={})
        else:
            super().__init__(conf_data=_normalize_conf_data(conf_data))

    def get_value_with_type(
        self, key: str, config_type: Type[Any], default: Any
    ) -> Any:
        """Helper method for all the get_xxx functions to avoid duplicate code.

        Args:
            key: The configuration key to retrieve
            config_type: The expected type of the configuration value (int, float, str,
            bool, timedelta, or an enum)
            default: The default value to return if key is not found

        Returns:
            The value associated with the key or the default value
        """
        value = self.conf_data.get(key)
        if value is None:
            return default

        if config_type is bool and isinstance(value, str):
            # The legacy flink-conf.yaml keeps values as strings and Flink
            # converts them when an option is read, accepting "true"
            # case-insensitively, so "false" must not stay truthy.
            return value.strip().lower() == "true"
        if config_type is timedelta:
            return _parse_duration_option(key, value)

        try:
            return config_type(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {key}: {value}"
            raise ValueError(msg) from e

    @override
    def get_int(self, key: str, default: int | None = None) -> int:
        return self.get_value_with_type(key, int, default)

    @override
    def get_float(self, key: str, default: float | None = None) -> float:
        return self.get_value_with_type(key, float, default)

    @override
    def get_bool(self, key: str, default: bool | None = None) -> bool:
        return self.get_value_with_type(key, bool, default)

    @override
    def get_str(self, key: str, default: str | None = None) -> str:
        return self.get_value_with_type(key, str, default)

    @override
    def get(self, option: ConfigOption) -> Any:
        if option.get_key() == _TRACE_LOG_TARGETS_KEY:
            return _parse_targets(
                self.conf_data.get(option.get_key(), option.get_default_value())
            )
        return self.get_value_with_type(
            option.get_key(), option.get_type(), option.get_default_value()
        )

    @override
    def set_str(self, key: str, value: str) -> None:
        self.conf_data[key] = value

    @override
    def set_int(self, key: str, value: int) -> None:
        self.conf_data[key] = value

    @override
    def set_float(self, key: str, value: float) -> None:
        self.conf_data[key] = value

    @override
    def set_bool(self, key: str, value: bool) -> None:
        self.conf_data[key] = value

    @override
    def set(self, option: ConfigOption, value: Any) -> None:
        if option.get_key() == _TRACE_LOG_TARGETS_KEY:
            stored = _serialize_targets(value)
        elif option.get_type() is timedelta:
            stored = format_duration(_parse_duration_option(option.get_key(), value))
        else:
            stored = value
        self.conf_data[option.get_key()] = stored

    def get_conf_data(self) -> dict:
        """Get the configuration data dictionary.

        Returns:
            dict: A dictionary containing all configuration items
        """
        return self.conf_data

    def get_config_data_by_prefix(self, prefix: str) -> dict:
        """Extract configuration items for a specific prefix from the configuration
        data.

        Parameters:
            prefix: The prefix for the key of configuration items.

        Returns:
            dict: A dictionary contains the configuration items for the specified
            key with the prefix. The keys are the configuration item names with
            the prefix removed, and the values are the corresponding configuration
            values.
        """
        prefix = f"{prefix}."
        result = {}
        for key, value in self.conf_data.items():
            if key.startswith(prefix):
                sub_key = key[len(prefix) :]
                result[sub_key] = value
        return result


def _parse_duration_option(key: str, value: Any) -> timedelta:
    """Parse and validate one duration option.

    The getter returns this timedelta. The setter formats it before storing it.
    """
    try:
        parsed = parse_duration(value)
    except ValueError as error:
        msg = f"Invalid duration for '{key}': {error}"
        raise ValueError(msg) from error
    return validate_consumed_duration(key, parsed)


def validate_consumed_duration(option_key: str, value: timedelta) -> timedelta:
    """Apply one option's precision rule.

    Retry wait and short-term memory TTL must be a whole number of milliseconds
    when positive. Other durations, including the async batch timeout, keep the
    parsed microsecond value.
    """
    if option_key == AgentExecutionOptions.RETRY_WAIT_INTERVAL.get_key():
        return require_whole_milliseconds(
            option_key, value, "or zero or negative to retry without waiting"
        )
    if option_key == AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.get_key():
        return require_short_term_memory_ttl(option_key, value)
    return value


def require_short_term_memory_ttl(option_key: str, value: timedelta) -> timedelta:
    """Reject a positive TTL that Flink would truncate to whole milliseconds.

    A non-positive value is unchanged so the caller can disable TTL.
    """
    if not is_positive(value):
        return value
    if value // timedelta(milliseconds=1) == 0:
        _invalid_option(
            option_key, f"must be at least 1 ms, got {format_duration(value)}"
        )
    return require_whole_milliseconds(
        option_key, value, "or zero or negative to disable TTL"
    )


def _invalid_option(option_key: str, reason: str) -> NoReturn:
    msg = f"{option_key} {reason}"
    raise ValueError(msg)
