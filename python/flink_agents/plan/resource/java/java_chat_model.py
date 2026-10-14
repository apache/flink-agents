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
from typing import Any, Dict, List, Mapping, Sequence

from pydantic import ConfigDict
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    StructuredOutputStrategy,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool
from flink_agents.plan.resource.java.conversions import (
    from_java_chat_result,
)
from flink_agents.plan.resource.java.java_resource_adapter import JavaResourceAdapter


def _to_java_chat_message(
    j_resource_adapter: JavaResourceAdapter, message: ChatMessage
) -> Any:
    """Build a Java message from fields extracted on the Python calling thread.

    Content crosses as block maps so media blocks survive the bridge.
    """
    return j_resource_adapter.fromPythonChatMessage(message.model_dump(mode="json"))


class JavaChatModelConnection(BaseChatModelConnection):
    """Java-based implementation of ChatModelConnection that wraps a Java chat model
    object.

    This class serves as a bridge between Python and Java chat model environments, but
    unlike JavaChatModelSetup, it does not provide direct chat functionality in Python.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True, extra="ignore")

    _j_resource: Any
    _j_resource_adapter: JavaResourceAdapter

    def __init__(
        self, j_resource: Any, j_resource_adapter: JavaResourceAdapter, **kwargs: Any
    ) -> None:
        """Creates a new JavaChatModelSetup.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        super().__init__(**kwargs)
        self._j_resource = j_resource
        self._j_resource_adapter = j_resource_adapter

    @override
    def set_metric_group(self, metric_group: Any) -> None:
        super().set_metric_group(metric_group)
        self._j_resource_adapter.set_metric_group(self._j_resource, metric_group)

    @override
    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Chat by forwarding the request to the wrapped Java connection.

        This connection serves as a Java resource wrapper only.
        Chat operations should be performed on the Java side using the underlying Java
        chat model object.

        A non-``None`` ``output_schema`` is rejected: only messages, tools and kwargs
        cross to the Java three-argument ``chat``, so a schema forwarded here would be
        dropped on the way. Declaring the parameter keeps a caller-supplied schema out
        of ``**kwargs``, which crosses to Java as ``modelParams`` — a provider-facing
        map, not a channel for framework execution metadata.
        """
        self._reject_unsupported_output_schema(output_schema)
        java_messages = [
            _to_java_chat_message(self._j_resource_adapter, message)
            for message in messages
        ]
        java_tools = [
            self._j_resource_adapter.getResource(tool.name, ResourceType.TOOL.value)
            for tool in tools
        ]
        j_response_message = self._j_resource.chat(java_messages, java_tools, kwargs)

        return from_java_chat_result(j_response_message)

    @override
    def close(self) -> None:
        self._j_resource.close()


class JavaChatModelSetup(BaseChatModelSetup):
    """Java-based implementation of ChatModelSetup that bridges Python and Java chat
    model functionality.

    This class wraps a Java chat model setup object and provides Python interface
    compatibility while delegating actual chat operations to the underlying Java
    implementation.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True, extra="ignore")

    _j_resource: Any
    _j_resource_adapter: JavaResourceAdapter

    def __init__(
        self, j_resource: Any, j_resource_adapter: JavaResourceAdapter, **kwargs: Any
    ) -> None:
        """Creates a new JavaChatModelSetup.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        # connection and model are required parameters for BaseChatModelSetup
        connection = kwargs.pop("connection", "")
        model = kwargs.pop("model", "")
        super().__init__(connection=connection, model=model, **kwargs)

        self._j_resource = j_resource
        self._j_resource_adapter = j_resource_adapter

    @override
    def set_metric_group(self, metric_group: Any) -> None:
        super().set_metric_group(metric_group)
        self._j_resource_adapter.set_metric_group(self._j_resource, metric_group)

    @property
    @override
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings.

        Returns:
            Empty dictionary as parameters are managed by Java side
        """
        return {}

    @override
    def open(self) -> None:
        """Open the java resource."""
        self._j_resource.open()
        resolved = self._j_resource.getSkills()
        self.skills = list(resolved) if resolved else None

    @override
    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Mapping[str, Any] | None = None,
        *,
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Execute chat conversation by delegating to Java implementation.

        1. Convert Python messages to Java format
        2. Call Java chat method with prompt-template arguments
        3. Convert Java response back to Python format

        Args:
            messages: Input message sequence.
            prompt_args: Prompt-template variables forwarded to the Java setup.
            tools: Not supported. Must be ``None``.
            output_schema: Not supported. Must be ``None``.
            **kwargs: Additional parameters passed to the model service.

        Returns:
            Model response result.

        Raises:
            NotImplementedError: If ``tools`` or ``output_schema`` is given. They
                are refused rather than dropped, or sent with the Java setup's bound
                prompt and tools, since the bridge reaches only the Java setup's
                three-argument ``chat``, which prepares the request itself.
        """
        if tools is not None or output_schema is not None:
            msg = (
                "A Java chat model setup cannot be sent explicit tools or an output "
                "schema from Python: the bridge carries only messages, prompt "
                "arguments and keyword arguments to the Java setup's three-argument "
                "chat, which adds its own bound prompt and tools. Bind tools and "
                "apply the schema on the Java side instead."
            )
            raise NotImplementedError(msg)
        # Convert Python messages to Java format
        java_messages = [
            _to_java_chat_message(self._j_resource_adapter, message)
            for message in messages
        ]
        j_response_message = self._j_resource.chat(
            java_messages, prompt_args or {}, kwargs
        )

        return from_java_chat_result(j_response_message)

    @override
    def will_apply_native_structured_output(
        self, output_schema: OutputSchema | None
    ) -> bool:
        """Never applies a schema natively, so a caller keeps describing the schema
        in the prompt, which works here.

        No connection is resolved on this side, and ``chat`` carries only messages,
        prompt arguments and keyword arguments across the bridge, so a schema has no
        way to travel natively.

        Raises:
            ValueError: If ``output_schema`` is not ``None`` and the strategy is
                ``NATIVE``, which this setup cannot honor.
        """
        if (
            output_schema is not None
            and self.structured_output_strategy == StructuredOutputStrategy.NATIVE
        ):
            msg = (
                "Structured output strategy NATIVE was requested, but a Java chat "
                "model setup reached from Python through JavaChatModelSetup cannot "
                "be given an output schema: the bridge carries only messages, prompt "
                "arguments and keyword arguments to the Java setup's chat. Use AUTO "
                "or PROMPT, or apply the schema on the Java side."
            )
            raise ValueError(msg)
        return False
