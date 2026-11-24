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
"""Abstract base connection resource for media model capabilities."""

from abc import ABC

from pydantic import ConfigDict
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType


class BaseMediaModelConnection(Resource, ABC):
    """Shared media model connection resource.

    Connections hold service credentials, endpoint settings, and shared SDK
    clients resolved by ``BaseMediaModelSetup`` subclasses when opened.
    """

    model_config = ConfigDict(extra="forbid")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type identifier for media model connections."""
        return ResourceType.MEDIA_MODEL_CONNECTION
