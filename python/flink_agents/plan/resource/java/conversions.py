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
from typing import Any

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.vector_stores.vector_store import Document


def from_java_chat_message(j_chat_message: Any) -> ChatMessage:
    """Reconstruct from the canonical map, including all nested blocks."""
    return ChatMessage.model_validate(j_chat_message.toMap())


def from_java_chat_result(j_response: Any) -> ChatResult:
    """Reconstruct a model response from its canonical map."""
    return ChatResult.model_validate(j_response.toMap())


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
