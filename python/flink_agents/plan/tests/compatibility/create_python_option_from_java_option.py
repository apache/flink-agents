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
from flink_agents.api.core_options import (
    AgentConfigOptions,
    AgentExecutionOptions,
    EventLogLevel,
    ShortTermMemoryTtlUpdate,
    ShortTermMemoryTtlVisibility,
)

# This script verifies that Python configuration options stay aligned with the
# Java AgentConfigOptions / AgentExecutionOptions definitions.
if __name__ == "__main__":
    assert AgentConfigOptions.BASE_LOG_DIR.get_key() == "baseLogDir"
    assert AgentConfigOptions.BASE_LOG_DIR.get_type() is str
    assert AgentConfigOptions.BASE_LOG_DIR.get_default_value() is None

    assert AgentConfigOptions.EVENT_LOG_LEVEL.get_key() == "event-log.level"
    assert AgentConfigOptions.EVENT_LOG_LEVEL.get_type() is EventLogLevel
    assert (
        AgentConfigOptions.EVENT_LOG_LEVEL.get_default_value() is EventLogLevel.STANDARD
    )

    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS.get_key()
        == "short-term-memory.state-ttl.ms"
    )
    assert AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS.get_type() is int
    assert AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS.get_default_value() == 0

    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE.get_key()
        == "short-term-memory.state-ttl.update-type"
    )
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE.get_type()
        is ShortTermMemoryTtlUpdate
    )
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE.get_default_value()
        is ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE
    )

    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY.get_key()
        == "short-term-memory.state-ttl.visibility"
    )
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY.get_type()
        is ShortTermMemoryTtlVisibility
    )
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY.get_default_value()
        is ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED
    )
