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
"""Observability-only record of one context management step."""

import copy
from typing import Any, ClassVar
from uuid import UUID

from flink_agents.api.events.event import BuiltInAttribute, Event


class ContextManagementEvent(Event):
    """One step in which the built-in chat action changed what it sends to the model.

    The event is emitted when a ``ContextManagementPolicy`` applies to a chat request,
    once per step and not once per model call: a tool result is reported when it is
    first sent truncated, a round when it is first masked, and a summarization when
    it runs. It is a record for logging, tracing and evaluation: it has no built-in
    consumer, and removing every listener of it does not change what the model
    receives.

    ``details`` depends on ``step``:

    - ``truncate``: ``max_chars`` and ``results``, a list with ``round``,
      ``call_id``, ``tool``, ``original_chars`` and ``sent_chars`` for each truncated
      result. ``round`` is the number of the result's round in the request, from 1:
      a provider can give the calls of two rounds the same id.
    - ``mask``: ``keep_last_rounds``, ``step_rounds``, ``rounds`` (how many rounds
      were masked in this step) and ``results``, a list with ``round``, ``call_id``,
      ``tool`` and ``omitted_chars`` for each masked result.
    - ``summarize``: ``summarizer``, ``rounds`` (how many rounds got their line in
      this step), ``summarized_rounds`` (how many rounds the summary that is sent
      has a line for; a round without a line is still sent), ``max_input_tokens``,
      ``estimated_input_tokens_before`` and ``estimate_approximate``, which tells
      whether the estimate before is an approximation. When the model is sent the
      new lines, ``summary_chars`` and ``estimated_input_tokens_after``, which is
      the estimate before, less what the lines save; the saving is always measured
      by the approximation. While the messages would not be smaller with the
      lines, they are held, and ``pending_rounds`` tells how many rounds have a
      line that is held. When the summary could not be written, ``error`` holds
      the reason and the request goes on without it; ``passed_over_round`` is
      then the number of the round that gets no line from this step on, when the
      answers for it could not be used in three steps in a row.
    """

    EVENT_TYPE: ClassVar[str] = "_context_management_event"

    # Tool results were cut to the policy's maximum size.
    STEP_TRUNCATE: ClassVar[str] = "truncate"
    # The tool results of older rounds were replaced by placeholders.
    STEP_MASK: ClassVar[str] = "mask"
    # Older rounds were replaced by a summary, or a summary could not be written.
    STEP_SUMMARIZE: ClassVar[str] = "summarize"

    _ATTRIBUTE_SCHEMA: ClassVar[tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_uuid("request_id"),
        BuiltInAttribute.required("model", str),
        BuiltInAttribute.required("step", str),
        BuiltInAttribute.required("details", dict),
    )

    def __init__(
        self,
        *,
        request_id: UUID,
        model: str,
        step: str,
        details: dict[str, Any],
    ) -> None:
        """Create a context management event with Java-compatible attributes.

        Args:
            request_id: The id of the chat request whose model input was changed.
            model: The chat model resource the request is being sent to.
            step: One of ``STEP_TRUNCATE``, ``STEP_MASK``, ``STEP_SUMMARIZE``.
            details: What the step did, see the class description.
        """
        attrs = {
            "request_id": UUID(request_id)
            if isinstance(request_id, str)
            else request_id,
            "model": model,
            "step": step,
            "details": copy.deepcopy(details),
        }
        self._validate_attribute_schema(self.EVENT_TYPE, attrs, self._ATTRIBUTE_SCHEMA)
        super().__init__(type=self.EVENT_TYPE, attributes=attrs)

    @classmethod
    def from_event(cls, event: Event) -> "ContextManagementEvent":
        """Restore the event and its lineage at a serialization boundary."""
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        return cls(**event.attributes).reconstruct_from(event)

    @property
    def request_id(self) -> UUID:
        """The id of the chat request whose model input was changed."""
        return self.get_attr("request_id")

    @property
    def model(self) -> str:
        """The chat model resource the request is being sent to."""
        return self.get_attr("model")

    @property
    def step(self) -> str:
        """One of ``STEP_TRUNCATE``, ``STEP_MASK``, ``STEP_SUMMARIZE``."""
        return self.get_attr("step")

    @property
    def details(self) -> dict[str, Any]:
        """What the step did; the keys depend on ``step``."""
        return self.get_attr("details")
