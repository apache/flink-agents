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
"""Public media model abstractions and job types."""

from flink_agents.api.media.async_job_model import (
    DEFAULT_POLL_INTERVAL,
    DEFAULT_WAIT_TIMEOUT,
    AsyncJobModel,
)
from flink_agents.api.media.media_job import (
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
)
from flink_agents.api.media.media_model_connection import BaseMediaModelConnection
from flink_agents.api.media.media_model_setup import (
    BaseImageGenerationModelSetup,
    BaseMediaModelSetup,
    BaseSpeechSynthesisModelSetup,
    BaseTranscriptionModelSetup,
    BaseVideoGenerationModelSetup,
)
from flink_agents.api.media.media_result import MediaResult

__all__ = [
    "DEFAULT_POLL_INTERVAL",
    "DEFAULT_WAIT_TIMEOUT",
    "AsyncJobModel",
    "BaseImageGenerationModelSetup",
    "BaseMediaModelConnection",
    "BaseMediaModelSetup",
    "BaseSpeechSynthesisModelSetup",
    "BaseTranscriptionModelSetup",
    "BaseVideoGenerationModelSetup",
    "MediaJob",
    "MediaJobError",
    "MediaJobState",
    "MediaJobStatus",
    "MediaJobTimeoutError",
    "MediaResult",
]
