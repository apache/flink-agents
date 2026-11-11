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
"""Shared DashScope utility functions."""

import os
from typing import Any

import dashscope


def create_client(
    *,
    api_key: str,
    base_url: str | None,
    workspace: str | None,
    region: str,
    timeout: float,
) -> dashscope.Client:
    """Create a client that does not read local config and does not retry."""
    return dashscope.Client(
        api_key=api_key,
        base_url=base_url,
        workspace=workspace,
        region=region,
        timeout=timeout,
        max_retries=0,
        config_path=os.devnull,
    )


def usage_counters(usage: Any) -> dict[str, int | float | str]:
    """Return scalar usage fields from an SDK object or a response mapping.

    Boolean values are omitted. In Python they are integers, but they are not
    usage counters.
    """
    if usage is None:
        return {}
    raw = (
        usage.model_dump(exclude_defaults=True)
        if hasattr(usage, "model_dump")
        else usage
    )
    if not isinstance(raw, dict):
        return {}
    return {
        key: value
        for key, value in raw.items()
        if isinstance(value, int | float | str) and not isinstance(value, bool)
    }
