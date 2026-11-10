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
import logging
from pathlib import Path
from typing import Any, Dict, Mapping

import yaml

from flink_agents.api.version_compatibility import flink_version_manager
from flink_agents.plan.configuration import AgentConfiguration

logger = logging.getLogger(__name__)

CONFIG_FILE_NAME = "config.yaml"
LEGACY_CONFIG_FILE_NAME = "flink-conf.yaml"
_AGENT_CONFIG_SECTION = "agent"
_AGENT_CONFIG_PREFIX = "agent."


def is_flink_2_or_later() -> bool:
    """Whether the Flink running the job is 2.0 or later.

    ``flink_version_manager.ge`` returns False when PyFlink is not installed, so
    an unknown version counts as Flink 1.x. That keeps the pre-2.0 behaviour of
    trying both files, and means a missing version never breaks a job.

    Returns:
        True if the running Flink is 2.0 or later, False otherwise.
    """
    return flink_version_manager.ge("2.0.0")


def load_agent_configuration(config_dir: str | None) -> AgentConfiguration:
    """Load the agent configuration the way Flink selects its configuration file.

    On Flink 1.x the legacy ``flink-conf.yaml`` wins when it exists and is parsed
    with the legacy line-based rules, otherwise ``config.yaml`` is parsed as
    standard YAML. Flink 2.x reads only ``config.yaml``. Standard YAML accepts
    settings nested under ``agent`` and flattened ``agent.<key>`` keys. They are
    expanded in file order, with the last value for each expanded key winning.
    The legacy file accepts only flattened ``agent.<key>`` keys.

    Args:
        config_dir: Directory holding the Flink configuration, typically
            ``FLINK_CONF_DIR``. None means no configuration file is loaded.

    Returns:
        The loaded configuration; empty when ``config_dir`` is None.

    Raises:
        RuntimeError: If the selected configuration file is missing or cannot be
            parsed.
    """
    if config_dir is None:
        return AgentConfiguration()

    try:
        return _load_configuration(Path(config_dir))
    except Exception as error:
        msg = f"Failed to load Flink Agents configuration from {config_dir}"
        raise RuntimeError(msg) from error


def _load_configuration(conf_dir: Path) -> AgentConfiguration:
    """Select and parse the configuration file of a Flink configuration directory.

    Args:
        conf_dir: Directory holding the Flink configuration.

    Returns:
        The loaded configuration.
    """
    legacy_file = conf_dir / LEGACY_CONFIG_FILE_NAME
    if is_flink_2_or_later():
        if legacy_file.exists():
            logger.warning(
                "Ignoring %s: Flink 2.x reads only %s",
                legacy_file,
                CONFIG_FILE_NAME,
            )
        return _load_yaml_config(conf_dir / CONFIG_FILE_NAME)

    if legacy_file.exists():
        logger.info(
            "Using legacy parser to load Flink Agents configuration from %s",
            legacy_file,
        )
        return AgentConfiguration(
            _flat_agent_options(_parse_legacy_flink_conf(legacy_file))
        )

    return _load_yaml_config(conf_dir / CONFIG_FILE_NAME)


def _load_yaml_config(config_file: Path) -> AgentConfiguration:
    """Read the agent settings of a standard YAML configuration file.

    Args:
        config_file: The ``config.yaml`` file to read.

    Returns:
        The loaded configuration.
    """
    with config_file.open(encoding="utf-8") as file:
        raw_config = yaml.safe_load(file)
    return AgentConfiguration(_extract_agent_config(raw_config, str(config_file)))


def _parse_legacy_flink_conf(path: str | Path) -> dict[str, str]:
    """Parse a legacy ``flink-conf.yaml`` the way Flink 1.20 does.

    Mirrors ``GlobalConfiguration#loadLegacyYAMLResource`` of Flink 1.20, which
    reads the file line by line rather than as YAML: everything after the first
    ``#`` is a comment, key and value are split on the first ``": "``, lines
    without a valid pair are skipped with a warning, values are kept as strings,
    and a duplicate key overrides the earlier one.

    Args:
        path: The legacy configuration file to read.

    Returns:
        The parsed key-value pairs, in file order.

    Raises:
        OSError: If the file cannot be read.
    """
    config: dict[str, str] = {}

    with Path(path).open(encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            conf = line.split("#", 1)[0].strip()
            if not conf:
                continue

            key, separator, value = conf.partition(": ")
            if not separator:
                logger.warning(
                    "Error while trying to split key and value in configuration "
                    "file %s:%s: Line is not a key-value pair "
                    "(missing space after ':'?)",
                    path,
                    line_no,
                )
                continue

            key = key.strip()
            value = value.strip()
            if not key or not value:
                logger.warning(
                    "Error after splitting key and value in configuration file "
                    "%s:%s: Key or value was empty",
                    path,
                    line_no,
                )
                continue

            config[key] = value

    return config


def _flat_agent_options(config: Mapping[str, Any]) -> Dict[str, Any]:
    """Collect top-level ``agent.<key>`` entries under ``<key>``.

    Args:
        config: A parsed configuration file, whose keys may carry the
                ``agent.`` prefix.

    Returns:
        Dict[str, Any]: The entries with the prefix removed, in input order.
    """
    return {
        key[len(_AGENT_CONFIG_PREFIX) :]: value
        for key, value in config.items()
        if isinstance(key, str) and key.startswith(_AGENT_CONFIG_PREFIX)
    }


def _extract_agent_config(raw_config: Any, config_path: str) -> Dict[str, Any]:
    """Select agent settings from a parsed YAML configuration file.

    Flink configuration files can declare agent settings either in a nested
    ``agent`` mapping or as flat ``agent.<key>`` entries. Expand them in file
    order before selecting agent settings, so the last value for each expanded
    key wins, matching Flink.

    Args:
        raw_config: The document returned by ``yaml.safe_load``.
        config_path: Path of the configuration file, used in error messages.

    Returns:
        Dict[str, Any]: The agent configuration with the ``agent.`` prefix removed.
    """
    if raw_config is None:
        return {}
    if not isinstance(raw_config, dict):
        msg = (
            f"Invalid agent configuration file {config_path}: "
            "expected a YAML mapping at the top level"
        )
        raise TypeError(msg)

    nested = raw_config.get(_AGENT_CONFIG_SECTION, {})
    if nested is None:
        nested = {}
    if not isinstance(nested, dict):
        msg = (
            f"Invalid agent configuration file {config_path}: "
            f"'{_AGENT_CONFIG_SECTION}' must be a mapping"
        )
        raise TypeError(msg)

    return _flat_agent_options(_flatten_dict(raw_config))


def _flatten_dict(d: Dict, parent_key: str = "", sep: str = ".") -> Dict[str, Any]:
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
        if isinstance(v, dict) and v:
            items.update(_flatten_dict(v, new_key, sep=sep))
        else:
            items[new_key] = v
    return items
