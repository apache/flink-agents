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
"""Output schema and recording Ollama connection for the ReAct native
structured-output e2e test.

Both live outside the test module because the agent runs in an interpreter inside
the Flink job: it restores the output schema and instantiates the connection by
module path, so this module must import without side effects.
"""

import json
import threading
from pathlib import Path
from typing import Any

from ollama import ChatResponse, Client
from pydantic import BaseModel, Field

from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)

_RECORD_LOCK = threading.Lock()


class CalcResult(BaseModel):
    """The value of the calculation."""

    result: int


class _RecordingClient:
    """Forwards ``chat`` to the Ollama client and appends one JSON line per request.

    Each line holds the names of the tools sent (``None`` when none were passed),
    the ``format`` sent (``None`` when absent) and the names of the tools the model
    called. The record goes to a file because the test reads it from another process.
    """

    def __init__(self, delegate: Client, record_file: Path) -> None:
        self._delegate = delegate
        self._record_file = record_file

    def chat(self, **kwargs: Any) -> ChatResponse:
        response = self._delegate.chat(**kwargs)
        tools = kwargs.get("tools")
        tool_names = None if tools is None else [t["function"]["name"] for t in tools]
        record = {
            "tools": tool_names,
            "format": kwargs.get("format"),
            "tool_calls": [
                call.function.name for call in response.message.tool_calls or []
            ],
        }
        with _RECORD_LOCK, self._record_file.open("a") as f:
            f.write(json.dumps(record) + "\n")
        return response


class RecordingOllamaChatModelConnection(OllamaChatModelConnection):
    """Ollama connection that records every request it sends to the server."""

    record_file: str = Field(
        description="JSONL file each Ollama chat request is appended to."
    )

    @property
    def client(self) -> _RecordingClient:
        """Return the Ollama client wrapped in a recorder."""
        return _RecordingClient(super().client, Path(self.record_file))
