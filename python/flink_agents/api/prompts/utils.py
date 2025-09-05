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
import re
from typing import Dict, Optional


class SafeFormatter:
    """Safe string formatter that does not raise KeyError if key is missing."""

    def __init__(self, kwargs: Optional[Dict[str, str]] = None) -> None:
        """Init method."""
        self.kwargs = kwargs or {}

    def format(self, text: str) -> str:
        """Format a text with key arguments."""
        return re.sub(r"\{([^{}]+)\}", self._replace_match, text)

    def _replace_match(self, match: re.Match) -> str:
        key = match.group(1)
        return str(self.kwargs.get(key, match.group(0)))


def format_string(text: str, **kwargs: str) -> str:
    """Format a string with kwargs."""
    formatter = SafeFormatter(kwargs=kwargs)
    return formatter.format(text)
