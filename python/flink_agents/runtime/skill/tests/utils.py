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
################################################################################
"""Configuration fixtures for skill tests."""

from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.skill.repository._materialize import (
    MaterializerLimits,
    limits_from_config,
)


def default_limits() -> MaterializerLimits:
    """Build the default test limits through the same config path as the runtime."""
    return limits_from_config(AgentConfiguration())
