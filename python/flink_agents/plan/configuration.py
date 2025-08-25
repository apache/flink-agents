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
from typing import Any, Dict, Optional

import yaml
from pydantic import BaseModel
from pyflink.common import Configuration
from typing_extensions import override

from flink_agents.api.configuration import (
    ConfigOption,
    ReadableConfiguration,
    WritableConfiguration,
)


def flatten_dict(d: Dict, parent_key: str = '', sep: str = '.') -> Dict[str, Any]:
    """Flatten a nested dictionary into a single-level dictionary.

    This function recursively traverses the dictionary, converting multi-level
    nested key-value pairs into a single-level structure, where nested levels
    are represented by joining key names with the specified separator.

    Args:
        d (Dict): The nested dictionary to be flattened
        parent_key (str): The parent key name, used in recursion to track the
                         upper-level key path. Defaults to an empty string.
        sep (str): The separator used to join parent and child keys.
                  Defaults to dot ('.').

    Returns:
        Dict[str, Any]: A flattened single-level dictionary where keys from
                       the original nested structure are joined with the separator
    """
    items = {}
    for k, v in d.items():
        new_key = f"{parent_key}{sep}{k}" if parent_key else k
        if isinstance(v, dict):
            items.update(flatten_dict(v, new_key, sep=sep))
        else:
            items[new_key] = v
    return items

class AgentConfiguration(BaseModel, WritableConfiguration, ReadableConfiguration):
    """Base class for config objects in the system.
    Provides a flat dict interface to access nested config values.
    """

    conf_data: Dict[str, Any]

    def __init__(self, conf_data: Optional[Dict[str, Any]] = None) -> None:
        """Initialize with optional configuration data."""
        if conf_data is None:
            super().__init__(conf_data = {})
        else:
            super().__init__(conf_data = conf_data)

    @override
    def get_int(self, key: str, default: Optional[int]=None) -> int:
        value = self.conf_data.get(key)
        if value is None:
            if default is None:
                msg = f"Missing key: {key}"
                raise KeyError(msg)
            return default

        try:
            return int(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {key}: {value}"
            raise ValueError(msg) from e

    @override
    def get_float(self, key: str, default: Optional[float]=None) -> float:
        value = self.conf_data.get(key)
        if value is None:
            if default is None:
                msg = f"Missing key: {key}"
                raise KeyError(msg)
            return default

        try:
            return float(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {key}: {value}"
            raise ValueError(msg) from e

    @override
    def get_bool(self, key: str, default: Optional[bool]=None) -> bool:
        value = self.conf_data.get(key)
        if value is None:
            if default is None:
                msg = f"Missing key: {key}"
                raise KeyError(msg)
            return default

        try:
            return bool(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {key}: {value}"
            raise ValueError(msg) from e

    @override
    def get_str(self, key: str, default: Optional[str]=None) -> str:
        value = self.conf_data.get(key)
        if value is None:
            if default is None:
                msg = f"Missing key: {key}"
                raise KeyError(msg)
            return default

        try:
            return str(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {key}: {value}"
            raise ValueError(msg) from e

    @override
    def get(self, option: ConfigOption) -> Any:
        value = self.conf_data.get(option.get_key())
        if value is None:
            if option.get_default_value() is None:
                msg = f"Missing key: {option.get_key()}"
                raise KeyError(msg)
            return option.get_default_value()
        try:
            return option.get_type()(value)
        except (ValueError, TypeError) as e:
            msg = f"Invalid value for {option.get_key()}: {value}"
            raise ValueError(msg) from e

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
        self.conf_data[option.get_key()] = value

    def load_from_file(self, config_path: Optional[str] = None) -> None:
        """Load configuration from a YAML file and update current configuration data.

        Args:
            config_path (str, optional): Path to the configuration file.
        """
        if config_path:
            path = Path(config_path)
            with path.open() as f:
                raw_config = yaml.safe_load(f)
                self.conf_data.update(flatten_dict(raw_config.get('Agent', {})))

    def get_conf_data(self) -> dict:
        """Get the configuration data dictionary.

        Returns:
            dict: A dictionary containing all configuration items
        """
        return self.conf_data

    def revert_to_flink_config(self) -> Configuration:
        """Revert LocalConfiguration to Flink configuration."""
        flink_config = Configuration()
        for key in self.conf_data:
            value = self.conf_data[key]
            if isinstance(value, bool):
                flink_config.set_boolean(key, value)
            elif isinstance(value, int):
                flink_config.set_integer(key, value)
            elif isinstance(value, float):
                flink_config.set_float(key, value)
            elif isinstance(value, str):
                flink_config.set_string(key, value)
            else:
                flink_config.set_string(key, str(value))
        return flink_config

    def get_config_data_by_prefix(self, prefix: str) -> dict:
        """Extract configuration items for a specific module from the configuration
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
