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

from typing import ClassVar


class ExecutionLifecycleEvents:
    """Framework-owned execution lifecycle Event types and statuses."""

    EXECUTION_STARTED_EVENT_TYPE = "_execution_started_event"
    EXECUTION_FINISHED_EVENT_TYPE = "_execution_finished_event"
    EXECUTION_FAILED_EVENT_TYPE = "_execution_failed_event"
    EXECUTION_REUSED_EVENT_TYPE = "_execution_reused_event"

    STATUS_STARTED = "started"
    STATUS_SUCCESS = "success"
    STATUS_FAILED = "failed"
    STATUS_REUSED = "reused"

    _EXPECTED_STATUS_BY_EVENT_TYPE: ClassVar[dict[str, str]] = {
        EXECUTION_STARTED_EVENT_TYPE: STATUS_STARTED,
        EXECUTION_FINISHED_EVENT_TYPE: STATUS_SUCCESS,
        EXECUTION_FAILED_EVENT_TYPE: STATUS_FAILED,
        EXECUTION_REUSED_EVENT_TYPE: STATUS_REUSED,
    }
    EVENT_TYPES = frozenset(_EXPECTED_STATUS_BY_EVENT_TYPE)

    @classmethod
    def expected_status(cls, event_type: str) -> str | None:
        """Return the status required by a framework lifecycle Event type."""
        return cls._EXPECTED_STATUS_BY_EVENT_TYPE.get(event_type)
