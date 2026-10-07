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
from typing import Any, Dict

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.vector_stores.vector_store import Document


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


def dump_blocks(chat_message: ChatMessage) -> list[Dict[str, Any]]:
    """Content blocks as plain dicts in the serialized shape, for the Java bridge."""
    return [
        block.model_dump(mode="json", exclude_none=True)
        for block in chat_message.blocks
    ]


def from_java_chat_message(j_chat_message: Any) -> ChatMessage:
    """Convert a chat message to a python chat message."""
    return ChatMessage.model_validate(
        {
            "role": MessageRole(j_chat_message.getRole().getValue()),
            "blocks": j_chat_message.getBlocksAsMaps(),
            "tool_calls": [
                normalize_tool_call_id(tool_call)
                for tool_call in j_chat_message.getToolCalls()
            ],
            "extra_args": j_chat_message.getExtraArgs(),
        }
    )


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
