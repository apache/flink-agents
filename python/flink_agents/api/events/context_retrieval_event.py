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
from typing import List
from uuid import UUID

from flink_agents.api.events.event import Event
from flink_agents.api.vector_stores.vector_store import Document


class ContextRetrievalRequestEvent(Event):
    """Event representing a request for context retrieval.

    Attributes:
    ----------
    query : str
        The search query text to find relevant context for
    vector_store : str
        Name of the vector store setup resource to use
    max_results : int
        Maximum number of results to return (default: 3)
    """
    query: str
    vector_store: str
    max_results: int = 3


class ContextRetrievalResponseEvent(Event):
    """Event representing retrieved context results.

    Attributes:
    ----------
    request_id : UUID
        ID of the original request event
    query : str
        The original search query from the request
    documents : List[Document]
        List of retrieved documents from the vector store
    """
    request_id: UUID
    query: str
    documents: List[Document]
