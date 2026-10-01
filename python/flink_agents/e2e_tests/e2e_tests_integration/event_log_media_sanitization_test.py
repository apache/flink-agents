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
"""E2e test: cross-language media payloads are sanitized in the Event Log.

A Python action emits a multimodal ``ChatRequestEvent`` through the documented
public API (``ctx.send_event``). The event crosses the Python -> Java bridge as
wire JSON, where ``Event.fromJson`` restores it to its concrete built-in type so
its messages become typed ``ChatMessage`` objects. Only a typed message engages
the Event Log's media sanitizer, so this test regresses the built-in-type
restoration at the JSON boundary: had the event stayed generic, its messages
would be logged verbatim and the inline Base64 payload and the pre-signed URL
credentials would leak.

This is the end-to-end counterpart of the runtime-layer unit test
``FileEventLoggerTest``, which drives the same reconstruction by calling
``Event.fromJson`` directly instead of through a real pipeline.
"""

import json
import os
import sysconfig
from pathlib import Path
from typing import Any, Dict, Sequence

from pyflink.common import Configuration
from pyflink.datastream import StreamExecutionEnvironment
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import (
    ChatMessage,
    DocumentBlock,
    ImageBlock,
    MessageRole,
    TextBlock,
)
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext

# Include both the active environment and any source checkout already on PYTHONPATH
# so the embedded interpreter can resolve this test module.
_purelib = sysconfig.get_paths()["purelib"]
_extra_pythonpath = os.environ.get("PYTHONPATH")
os.environ["PYTHONPATH"] = (
    f"{_purelib}{os.pathsep}{_extra_pythonpath}" if _extra_pythonpath else _purelib
)

# An inline Base64 payload that must never survive into the Event Log.
PAYLOAD = "aW5saW5lLXBheWxvYWQtYnl0ZXM="
# A pre-signed URL whose credentials and query must never survive into the Event Log.
SIGNED_URL = "https://user:secret@example.org/media/cat.png?X-Amz-Signature=abc123"
# The credential-free, query-free form the Event Log is allowed to keep.
STRIPPED_URL = "https://example.org/media/cat.png"
QUESTION = "what is in this picture?"


class MediaSanitizationChatModel(BaseChatModelSetup):
    """Mock chat model that consumes a multimodal message and echoes its text."""

    def open(self) -> None:
        """Do nothing: there is no real connection to resolve."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return no provider kwargs."""
        return {}

    @override
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Return a deterministic text reply, projecting away any media blocks."""
        return ChatMessage.of(MessageRole.ASSISTANT, f"answered: {messages[-1].text}")


class MediaSanitizationAgent(Agent):
    """Agent whose input action emits a multimodal chat request."""

    @chat_model_setup
    @staticmethod
    def mock_chat_model() -> ResourceDescriptor:
        """Chat model referenced by the ChatRequestEvent."""
        return ResourceDescriptor(
            clazz=(
                f"{MediaSanitizationChatModel.__module__}."
                f"{MediaSanitizationChatModel.__name__}"
            ),
            connection="placement",
            model="mock-model",
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Send a multimodal ChatRequestEvent using only documented public API."""
        question = InputEvent.from_event(event).input["review"]
        ctx.send_event(
            ChatRequestEvent(
                model="mock_chat_model",
                messages=[
                    ChatMessage.user(
                        [
                            TextBlock(text=question),
                            ImageBlock.from_base64("image/png", PAYLOAD),
                            DocumentBlock.from_url(
                                "application/pdf",
                                SIGNED_URL,
                                name="cat.pdf",
                                size_bytes=42,
                            ),
                        ]
                    )
                ],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the chat model reply so the pipeline terminates."""
        response = ChatResponseEvent.from_event(event).response
        ctx.send_event(OutputEvent(output={"answer": response.text}))


def _read_event_log(event_log_dir: Path) -> tuple[list[dict], str]:
    """Read parsed records and the raw text from the event log files."""
    log_files = list(event_log_dir.glob("events-*.log"))
    assert log_files, f"Expected event log files in {event_log_dir}"
    raw_chunks: list[str] = []
    records: list[dict] = []
    for log_file in log_files:
        text = log_file.read_text(encoding="utf-8")
        raw_chunks.append(text)
        records.extend(json.loads(line) for line in text.splitlines() if line.strip())
    return records, "\n".join(raw_chunks)


def test_cross_language_media_sanitized_in_event_log(tmp_path: Path) -> None:
    """A Python-emitted multimodal chat request is sanitized in the Event Log.

    Runs a real MiniCluster: the multimodal ``ChatRequestEvent`` crosses the
    Python -> Java bridge, is restored to its concrete built-in type, and is
    logged with media payloads and URL credentials stripped. VERBOSE lifts
    truncation but not sanitization, so a sanitizer gap would leak the payload
    verbatim rather than hiding it behind a truncation limit.
    """
    event_log_dir = tmp_path / "event_log"

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set_str("baseLogDir", str(event_log_dir))
    agents_env.get_config().set_str("event-log.level", "VERBOSE")

    input_datastream = env.from_collection([{"id": 1, "review": QUESTION}])
    output_datastream = (
        agents_env.from_datastream(
            input=input_datastream, key_selector=lambda value: value["id"]
        )
        .apply(MediaSanitizationAgent())
        .to_datastream()
    )
    list(output_datastream.execute_and_collect())

    records, raw_log = _read_event_log(event_log_dir)

    # Core regression: neither the inline payload nor the URL credentials survive.
    assert PAYLOAD not in raw_log, "inline Base64 payload leaked into the Event Log"
    assert "secret" not in raw_log, "URL userinfo leaked into the Event Log"
    assert "X-Amz-Signature" not in raw_log, "URL query leaked into the Event Log"

    # The request was restored to its concrete type, so its message kept media
    # metadata but dropped every payload byte and credential.
    chat_request = next(
        record
        for record in records
        if record["eventType"] == ChatRequestEvent.EVENT_TYPE
    )
    blocks = chat_request["eventAttributes"]["messages"][0]["blocks"]

    assert blocks[0]["text"] == QUESTION
    image = blocks[1]
    assert image["media_type"] == "image/png"
    assert image["source"]["type"] == "base64"
    assert "data" not in image["source"], "inline data must be dropped, not masked"
    assert image["size_bytes"] > 0, "derived size metadata should be kept"
    document = blocks[2]
    assert document["media_type"] == "application/pdf"
    assert document["source"]["type"] == "url"
    assert document["source"]["url"] == STRIPPED_URL
    assert document["name"] == "cat.pdf"
    assert document["size_bytes"] == 42
