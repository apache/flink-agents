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
import importlib
import json
import typing
from typing import Any, Callable, Dict

import cloudpickle

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.resource import Resource, ResourceType, get_resource_class
from flink_agents.api.tools.tool import Tool, ToolMetadata
from flink_agents.api.tools.utils import (
    create_java_tool_schema_str_from_model,
    create_model_from_java_tool_schema_str,
)
from flink_agents.api.vector_stores.vector_store import (
    Collection,
    Document,
    VectorStoreQuery,
    VectorStoreQueryMode,
    VectorStoreQueryResult,
)
from flink_agents.plan.resource_provider import JAVA_RESOURCE_MAPPING
from flink_agents.runtime.java.java_resource_wrapper import (
    JavaGetResourceWrapper,
    JavaPrompt,
    JavaTool,
)


def convert_to_python_object(bytesObject: bytes) -> Any:
    """Used for deserializing Python objects."""
    return cloudpickle.loads(bytesObject)


def _build_event_log_string(event: InputEvent | Event, event_type: str) -> str:
    try:
        payload = json.loads(event.model_dump_json())
        payload["eventType"] = event_type
        payload.setdefault("attributes", {})
        return json.dumps(payload)
    except Exception:
        return str(event)


def wrap_to_input_event(bytesObject: bytes) -> tuple[bytes, str]:
    """Wrap data to python input event and serialize.

    Returns:
        A tuple of (serialized_event_bytes, event_json_str)
    """
    event = InputEvent(input=cloudpickle.loads(bytesObject))
    event_type = f"{event.__class__.__module__}.{event.__class__.__qualname__}"
    return (cloudpickle.dumps(event), _build_event_log_string(event, event_type))


def get_output_from_output_event(bytesObject: bytes) -> Any:
    """Get output data from OutputEvent and serialize."""
    return cloudpickle.dumps(convert_to_python_object(bytesObject).output)

def create_resource(resource_module: str, resource_clazz: str, func_kwargs: Dict[str, Any]) -> Resource:
    """Dynamically create a resource instance from module and class name.

    Args:
        resource_module: The module path containing the resource class
        resource_clazz: The class name to instantiate
        func_kwargs: Keyword arguments to pass to the class constructor

    Returns:
        Resource: An instance of the specified resource class
    """
    module = importlib.import_module(resource_module)
    cls = getattr(module, resource_clazz)
    return cls(**func_kwargs)

def get_resource_function(j_resource_adapter: Any) -> Callable:
    """Create a callable wrapper for Java resource adapter.

    Args:
        j_resource_adapter: Java resource adapter object

    Returns:
        Callable: A Python callable that wraps the Java resource adapter
    """
    return JavaGetResourceWrapper(j_resource_adapter).get_resource

def from_java_tool(j_tool: Any) -> JavaTool:
    """Convert a Java tool object to a Python JavaTool instance.

    Args:
        j_tool: Java tool object

    Returns:
        JavaTool: Python wrapper for the Java tool with extracted metadata
    """
    name = j_tool.getName()
    metadata = ToolMetadata(
        name=name,
        description=j_tool.getDescription(),
        args_schema=create_model_from_java_tool_schema_str(name, j_tool.getMetadata().getInputSchema()),
    )
    return JavaTool(metadata=metadata)

def from_java_prompt(j_prompt: Any) -> JavaPrompt:
    """Convert a Java prompt object to a Python JavaPrompt instance.

    Args:
        j_prompt: Java prompt object to be wrapped

    Returns:
        JavaPrompt: Python wrapper for the Java prompt
    """
    return JavaPrompt(j_prompt=j_prompt)

def from_java_resource(type_name: str, kwargs: Dict[str, Any]) -> Resource:
    """Convert a Java resource object to a Python Resource instance.
    This function is used to convert a Java resource object to a Python Resource
    instance.

    Args:
        type_name: Java resource type name
        kwargs: Keyword arguments
    Returns:
        Resource: Python wrapper for the Java resource
    """
    class_path = JAVA_RESOURCE_MAPPING.get(ResourceType(type_name))
    if not class_path:
        err_msg = f"No Java resource mapping found for {type_name}"
        raise ValueError(err_msg)

    module_path, class_name = class_path.rsplit(".", 1)
    cls = get_resource_class(module_path, class_name)

    return cls(**kwargs)

def normalize_tool_call_id(tool_call: Dict[str, Any]) -> Dict[str, Any]:
    """Normalize tool call by converting the ID field to string format while preserving
    all other fields.

    This function ensures that the tool call ID is consistently represented as a string,
    which is required for compatibility with certain systems that expect string IDs.

    Args:
        tool_call: Dictionary containing tool call information. The dictionary may
                   contain any number of fields, but typically includes:
                  - id: Tool call identifier (will be converted to string)
                  - type: Tool call type (preserved as-is)
                  - function: Function details (preserved as-is)
                  - Any other fields (preserved as-is)
    """
    normalized_call = tool_call.copy()

    normalized_call["id"] = str(tool_call.get("id", ""))

    return normalized_call

def from_java_chat_message(j_chat_message: Any) -> ChatMessage:
    """Convert a chat message to a python chat message."""
    return ChatMessage(role=MessageRole(j_chat_message.getRole().getValue()),
                       content=j_chat_message.getContent(),
                       tool_calls=[normalize_tool_call_id(tool_call) for tool_call in j_chat_message.getToolCalls()],
                       extra_args=j_chat_message.getExtraArgs())


def to_java_chat_message(chat_message: ChatMessage) -> Any:
    """Convert a chat message to a java chat message."""
    from pemja import findClass
    j_ChatMessage = findClass("org.apache.flink.agents.api.chat.messages.ChatMessage")
    j_chat_message = j_ChatMessage()

    j_MessageRole = findClass("org.apache.flink.agents.api.chat.messages.MessageRole")
    j_chat_message.setRole(j_MessageRole.fromValue(chat_message.role.value))
    j_chat_message.setContent(chat_message.content)
    j_chat_message.setExtraArgs(chat_message.extra_args)
    if chat_message.tool_calls:
        tool_calls = [normalize_tool_call_id(tool_call) for tool_call in chat_message.tool_calls]
        j_chat_message.setToolCalls(tool_calls)

    return j_chat_message

# TODO: Replace this with `to_java_chat_message()` when the `find_class` bug is fixed.
def update_java_chat_message(chat_message: ChatMessage, j_chat_message: Any) -> str:
    """Update a Java chat message using Python chat message."""
    j_chat_message.setContent(chat_message.content)
    j_chat_message.setExtraArgs(chat_message.extra_args)
    if chat_message.tool_calls:
        tool_calls = [normalize_tool_call_id(tool_call) for tool_call in chat_message.tool_calls]
        j_chat_message.setToolCalls(tool_calls)

    return chat_message.role.value

def from_java_document(j_document: Any) -> Document:
    """Convert a Java documents to a Python document."""
    document = Document(
        content=j_document.getContent(),
        id=j_document.getId(),
        metadata=j_document.getMetadata(),
    )
    if j_document.getEmbedding():
        document.embedding = list(j_document.getEmbedding())
    return document

def update_java_document(document: Document, j_document: Any) -> None:
    """Update a Java document using Python document."""
    j_document.setContent(document.content)
    j_document.setId(document.id)
    j_document.setMetadata(document.metadata)
    if document.embedding:
        j_document.setEmbedding(tuple(document.embedding))


def from_java_vector_store_query(j_query: Any) -> VectorStoreQuery:
    """Convert a Java vector store query to a Python query."""
    return VectorStoreQuery(
        mode=VectorStoreQueryMode(j_query.getMode().getValue()),
        query_text=j_query.getQueryText(),
        limit=j_query.getLimit(),
        collection_name=j_query.getCollection(),
        extra_args=j_query.getExtraArgs()
    )

def from_java_vector_store_query_result(j_query: Any) -> VectorStoreQueryResult:
    """Convert a Java vector store query result to a Python query result."""
    return VectorStoreQueryResult(
        documents=[from_java_document(j_document) for j_document in j_query.getDocuments()],
    )

def from_java_collection(j_collection: Any) -> Collection:
    """Convert a Java collection to a Python collection."""
    return Collection(
        name=j_collection.getName(),
        metadata=j_collection.getMetadata(),
    )

def from_java_message_role(j_role: Any) -> MessageRole:
    """Convert a Java message role to a Python message role."""
    return MessageRole(j_role.getValue())

def get_java_tool_metadata_from_tool(tool: Tool) -> typing.Dict[str, str]:
    """Retrieve Java format tool metadata from a tool input schema string."""
    return {"name": tool.name, "description": tool.metadata.description, "inputSchema": create_java_tool_schema_str_from_model(tool.metadata.args_schema)}

def get_mode_value(query: VectorStoreQuery) -> str:
    """Get the mode value of a VectorStoreQuery."""
    return query.mode.value

def call_method(obj: Any, method_name: str, kwargs: Dict[str, Any]) -> Any:
    """Calls a method on `obj` by name and passes in positional and keyword arguments.

    Parameters:
        obj: Any Python object
        method_name: A string representing the name of the method to call
        kwargs: Keyword arguments to pass to the method

    Returns:
        The return value of the method

    Raises:
        AttributeError: If the object does not have the specified method
    """
    if not hasattr(obj, method_name):
        err_msg = f"Object {obj} has no attribute '{method_name}'"
        raise AttributeError(err_msg)

    method = getattr(obj, method_name)
    return method(**kwargs)
