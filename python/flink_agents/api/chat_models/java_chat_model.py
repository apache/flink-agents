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
from pydantic import ConfigDict

from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import java_resource


@java_resource
class JavaChatModelConnection(BaseChatModelConnection):
    """Java-based implementation of ChatModelConnection that wraps a Java chat model
    object.

    This class serves as a bridge between Python and Java chat model environments, but
    unlike JavaChatModelSetup, it does not provide direct chat functionality in Python.
    """

    # Java descriptors intentionally carry implementation-specific arguments (e.g.
    # java_clazz, or setup-specific keys like extract_reasoning) in an open map, so
    # this reverts the base class's strict extra="forbid" back to "ignore" rather
    # than making Java and Python validation semantics diverge.
    model_config = ConfigDict(arbitrary_types_allowed=True, extra="ignore")

    java_class_name: str = ""


@java_resource
class JavaChatModelSetup(BaseChatModelSetup):
    """Java-based implementation of ChatModelSetup that bridges Python and Java chat
    model functionality.

    This class wraps a Java chat model setup object and provides Python interface
    compatibility while delegating actual chat operations to the underlying Java
    implementation.
    """

    # See JavaChatModelConnection.model_config for rationale.
    model_config = ConfigDict(arbitrary_types_allowed=True, extra="ignore")

    java_class_name: str = ""
