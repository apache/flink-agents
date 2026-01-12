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
from typing import Any, Dict, List, Sequence

from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.java_chat_model import (
    JavaChatModelConnection,
    JavaChatModelSetup,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool


class JavaChatModelConnectionImpl(JavaChatModelConnection):
    """Java-based implementation of ChatModelConnection that wraps a Java chat model
    object.

    This class serves as a bridge between Python and Java chat model environments, but
    unlike JavaChatModelSetup, it does not provide direct chat functionality in Python.
    """


    _j_resource: Any
    _j_resource_adapter: Any

    def __init__(self, j_resource: Any, j_resource_adapter: Any, **kwargs: Any) -> None:
        """Creates a new JavaChatModelSetup.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        super().__init__(**kwargs)
        self._j_resource=j_resource
        self._j_resource_adapter=j_resource_adapter

    @override
    def chat(
            self,
            messages: Sequence[ChatMessage],
            tools: List[Tool] | None = None,
            **kwargs: Any,
    ) -> ChatMessage:
        """Chat method that throws UnsupportedOperationException.

        This connection serves as a Java resource wrapper only.
        Chat operations should be performed on the Java side using the underlying Java
        chat model object.
        """
        java_messages = [
            self._j_resource_adapter.fromPythonChatMessage(message)
            for message in messages
        ]
        java_tools = [
            self._j_resource_adapter.getResource(tool.name, ResourceType.TOOL.value) for tool in tools
        ]
        j_response_message = self._j_resource.chat(java_messages, java_tools, kwargs)

        # Convert Java response back to Python format
        from flink_agents.runtime.python_java_utils import (
            from_java_chat_message,
        )

        return from_java_chat_message(j_response_message)


class JavaChatModelSetupImpl(JavaChatModelSetup):
    """Java-based implementation of ChatModelSetup that bridges Python and Java chat
    model functionality.

    This class wraps a Java chat model setup object and provides Python interface
    compatibility while delegating actual chat operations to the underlying Java
    implementation.
    """

    _j_resource: Any
    _j_resource_adapter: Any

    def __init__(self, j_resource: Any, j_resource_adapter: Any, **kwargs: Any) -> None:
        """Creates a new JavaChatModelSetup.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        super().__init__(**kwargs)
        self._j_resource=j_resource
        self._j_resource_adapter=j_resource_adapter

    @property
    @override
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings.

        Returns:
            Empty dictionary as parameters are managed by Java side
        """
        return {}

    @override
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Execute chat conversation by delegating to Java implementation.

        1. Convert Python messages to Java format
        2. Call Java chat method
        3. Convert Java response back to Python format

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        **kwargs : Any
            Additional parameters passed to the model service

        Returns:
        -------
        ChatMessage
            Model response message
        """
        # Convert Python messages to Java format
        java_messages = [self._j_resource_adapter.fromPythonChatMessage(message) for message in messages]
        j_response_message = self._j_resource.chat(java_messages, kwargs)

        # Convert Java response back to Python format
        from flink_agents.runtime.python_java_utils import (
            from_java_chat_message,
        )
        return from_java_chat_message(j_response_message)
