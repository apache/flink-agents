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
import functools
import logging
from importlib.metadata import version
from typing import List, Tuple

from packaging import version as pkg_version

# For flink versions depend on pemja without fix
# pr: https://github.com/alibaba/pemja/pull/95.
# The async execution for cross language resource
# is not supported.
UNSUPPORTED_RANGES: List[Tuple[str, str]] = [
    ("1.0.0", "1.20.4"),
    ("2.0.0", "2.0.1"),
    ("2.1.0", "2.1.1"),
    ("2.2.0", "2.2.0"),
]


@functools.lru_cache(maxsize=1)
def support_async() -> bool:
    """Check whether the current Flink version supports the async execution for
     cross-language resource.

    The async execution for java resource is supported only on flink with
    the pemja 0.6.2 dependency. See https://github.com/apache/flink-agents/pull/571
    for details.
    """
    try:
        current = pkg_version.parse(version("apache-flink"))

        for min_ver, max_ver in UNSUPPORTED_RANGES:
            if pkg_version.parse(min_ver) <= current <= pkg_version.parse(max_ver):
                logging.debug(
                    f"Flink {current} doesn't support async execution for java resource, will fallback to sync execution."
                )
                return False
    except Exception:
        return False
    else:
        return True
