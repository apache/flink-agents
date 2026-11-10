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

from flink_agents.api.version_compatibility import flink_version_manager
from flink_agents.plan.configuration import AgentConfiguration, _flat_agent_options
from flink_agents.runtime.legacy_flink_conf_parser import parse_legacy_flink_conf

logger = logging.getLogger(__name__)

CONFIG_FILE_NAME = "config.yaml"
LEGACY_CONFIG_FILE_NAME = "flink-conf.yaml"


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
    standard YAML. Flink 2.x reads only ``config.yaml``. Both forms accept
    settings nested under ``agent`` and flattened ``agent.<key>`` keys, the flat
    keys winning over the nested ones.

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
            _flat_agent_options(parse_legacy_flink_conf(legacy_file))
        )

    return _load_yaml_config(conf_dir / CONFIG_FILE_NAME)


def _load_yaml_config(config_file: Path) -> AgentConfiguration:
    """Read the agent settings of a standard YAML configuration file.

    Args:
        config_file: The ``config.yaml`` file to read.

    Returns:
        The loaded configuration.
    """
    config = AgentConfiguration()
    config.load_from_file(str(config_file))
    return config
