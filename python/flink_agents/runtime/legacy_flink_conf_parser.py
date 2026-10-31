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
from typing import Dict

logger = logging.getLogger(__name__)


def parse_legacy_flink_conf(path: str | Path) -> Dict[str, str]:
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
    config: Dict[str, str] = {}

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
