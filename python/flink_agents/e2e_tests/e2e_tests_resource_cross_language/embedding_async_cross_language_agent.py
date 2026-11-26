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
import threading
from typing import Any

from pydantic import PrivateAttr

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ImageBlock, MediaBlock, TextBlock
from flink_agents.api.decorators import (
    action,
    embedding_model_connection,
    embedding_model_setup,
)
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingContent,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.api.runner_context import RunnerContext

_JAVA_FIXTURES = "org.apache.flink.agents.resource.test.EmbeddingAsyncCrossLanguageTest"


class BarrierConnection(BaseEmbeddingModelConnection):
    """A deterministic provider whose barrier proves actual overlapping calls."""

    _barrier: threading.Barrier = PrivateAttr(
        default_factory=lambda: threading.Barrier(2)
    )

    def embed(self, content: EmbeddingContent, **kwargs: Any) -> EmbeddingResult:
        assert kwargs["dimension"] == 1
        if kwargs.get("synchronize"):
            self._barrier.wait(timeout=15)
        single = isinstance(content, str | TextBlock | MediaBlock)
        inputs = [content] if single else content
        vectors = [
            [9.0]
            if isinstance(item, MediaBlock)
            else [float(len(item.text if isinstance(item, TextBlock) else item))]
            for item in inputs
        ]
        usage = EmbeddingTokenUsage(7, 9) if kwargs.get("report_usage") else None
        return EmbeddingResult(vectors[0] if single else vectors, usage)


class BarrierSetup(BaseEmbeddingModelSetup):
    """Use normal resource lookup and parameter merging."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        return {"dimension": 2, "report_usage": True}


class JavaEmbeddingAgent(Agent):
    """Run the Python-to-Java matrix on the action thread and async workers."""

    @embedding_model_connection
    @staticmethod
    def connection() -> ResourceDescriptor:
        return JavaResourceDescriptor(clazz=f"{_JAVA_FIXTURES}$BarrierConnection")

    @embedding_model_setup
    @staticmethod
    def model() -> ResourceDescriptor:
        return JavaResourceDescriptor(
            clazz=f"{_JAVA_FIXTURES}$BarrierSetup",
            connection="connection",
            model="fixture",
        )

    @action(EventType.InputEvent)
    @staticmethod
    async def embed(event: Event, ctx: RunnerContext) -> None:
        asynchronous = bool(InputEvent.from_event(event).input)
        connection = ctx.get_resource(
            "connection", ResourceType.EMBEDDING_MODEL_CONNECTION
        )
        setup = ctx.get_resource("model", ResourceType.EMBEDDING_MODEL)
        image = ImageBlock.from_base64("image/png", "aGk=")
        cases = [
            ("abc", [3.0]),
            (TextBlock(text="abc"), [3.0]),
            (image, [9.0]),
            (["abc"], [[3.0]]),
            ([TextBlock(text="abc"), image], [[3.0], [9.0]]),
            ([], []),
        ]
        for usage in (False, True):
            parameters = {
                "dimension": 1,
                "report_usage": usage,
                "synchronize": asynchronous,
            }
            for content, expected in cases:
                if asynchronous:
                    outcomes = await ctx.gather(
                        ctx.execute_async(connection.embed, content, **parameters),
                        ctx.execute_async(setup.embed, content, **parameters),
                    )
                    for outcome in outcomes:
                        if outcome.error is not None:
                            raise outcome.error
                    results = [outcome.value for outcome in outcomes]
                else:
                    results = [
                        connection.embed(content, **parameters),
                        setup.embed(content, **parameters),
                    ]
                for result in results:
                    assert result.embeddings == expected
                    assert result.token_usage == (
                        EmbeddingTokenUsage(7, 9) if usage else None
                    )
        ctx.send_event(OutputEvent(output="passed"))
