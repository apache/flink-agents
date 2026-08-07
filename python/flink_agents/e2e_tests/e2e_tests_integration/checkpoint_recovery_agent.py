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
"""Agent that parks a built-in tool-call round so a checkpoint can capture it.

A deterministic mock chat model emits one tool call, and the tool then blocks on a
filesystem handshake, so the harness rather than the clock decides when the agent
run may finish. While the run is parked, the built-in tool-call context and a
``bytes`` short-term-memory value sit in checkpointable state; the harness kills and
restarts the TaskManager, and the assertions below then run in a TaskManager process
that never performed any of the writes.

Submitted to a real cluster by ``checkpoint_recovery_job``. The module name is
deliberately neither ``*_test.py`` nor ``*_example.py``: the first would make pytest
collect it, the second would make the example-submission nightly submit it.
"""

import json
import sys
import time
import uuid
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any, Dict, List, Sequence

from pydantic import BaseModel
from pyflink.datastream import KeySelector

import flink_agents.api.memory_object as memory_object_module
from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools import InjectedArg
from flink_agents.api.tools.tool import Tool as BaseTool
from flink_agents.api.tools.tool import ToolType

# Config keys the submitting job must set; a missing one fails the run loudly.
HANDSHAKE_DIR_CONFIG_KEY = "handshake_dir"
VERDICT_DIR_CONFIG_KEY = "verdict_dir"

# Filenames exchanged with the harness.
TOOL_ENTERED_MARKER = "tool-entered"
RELEASE_MARKER = "release"
IDENTITY_MARKER = "runtime-identity.json"
VERDICT_MARKER = "verdict.json"

RELEASE_TIMEOUT_S = 240.0
POLL_INTERVAL_S = 0.2

# The user message content, also built into the input record by the job.
USER_CONTENT = "hold the tool call open across a taskmanager restart"

BLOCKING_TOOL_NAME = "block_until_released"

_ROUND_ONE_MARKER = "recovery-round-one-assistant"
_BLOB_MEMORY_KEY = "blob"

# Returned by the tool only on the released path, and required in the transcript.
# The framework converts a tool exception into the response string
# "Tool `block_until_released` execute failed." rather than failing the action, so a
# timed-out handshake reaches the assertions only as the absence of this value. That
# makes the value load-bearing: it must not be a substring of that failure string,
# which rules out anything built from the tool's own name.
_TOOL_SENTINEL = "handshake-done-sentinel"

# A NUL plus bytes that are not valid UTF-8, so a value handled as UTF-8 text anywhere
# on the path either raises or comes back corrupted. A single-byte codec such as
# latin-1 would round trip it intact, so this catches UTF-8 handling rather than every
# possible string conversion.
_KNOWN_BLOB = b"\x00\x01flink-agents\xff\xfe"

# Process-local and deliberately outside Flink state. Anything checkpointed is
# restored along with the payload and therefore cannot tell a restored read apart
# from the reader's own write.
#
# Pemja runs as ExecType.MULTI_THREAD against a singleton main interpreter, so these
# globals outlive an in-place task restart and are reset only when the TaskManager
# process itself dies. A read that returns the known value while they are still zero
# therefore proves the write happened in a different TaskManager process, which is
# stronger than proving it happened in a different interpreter.
_PROCESS_EPOCH = uuid.uuid4().hex
_BLOB_WRITES_IN_THIS_PROCESS = 0
_TOOL_CALLS_EMITTED_IN_THIS_PROCESS = 0


def _atomic_write(target: Path, content: str) -> None:
    """Publish a file by renaming it into place so no reader sees a partial write."""
    tmp = target.parent / f"{target.name}.tmp"
    tmp.write_text(content, encoding="utf-8")
    tmp.replace(target)


def await_release(
    handshake_dir: str,
    *,
    timeout_s: float = RELEASE_TIMEOUT_S,
    poll_interval_s: float = POLL_INTERVAL_S,
) -> None:
    """Announce that the tool was entered, then block until the harness releases it.

    The marker is written before the wait begins so the harness can order its kill
    strictly after the tool-call context reached checkpointable state.

    Raises ``TimeoutError`` at the deadline so the wait is bounded and the reason
    reaches the TaskManager log. The raise has no effect on control flow: the caller
    converts every tool exception into an ordinary tool response, so raising and
    returning drive the run identically. What makes a timeout observable is the
    sentinel the tool returns only on this function's success path.

    Parameters
    ----------
    handshake_dir : str
        Directory the harness created before submitting the job.
    timeout_s : float
        How long to wait for the release marker before failing the run.
    poll_interval_s : float
        Interval between existence checks.
    """
    base = Path(handshake_dir)
    _atomic_write(base / TOOL_ENTERED_MARKER, _PROCESS_EPOCH)

    release = base / RELEASE_MARKER
    deadline = time.monotonic() + timeout_s
    while not release.exists():
        if time.monotonic() > deadline:
            msg = f"waited {timeout_s}s for the release marker at {release}"
            raise TimeoutError(msg)
        time.sleep(poll_interval_s)


def _flink_agents_version() -> str:
    """Report the installed distribution version, or ``unknown`` if there is none."""
    try:
        return version("flink-agents")
    except PackageNotFoundError:
        return "unknown"


def _runtime_identity() -> Dict[str, Any]:
    """Describe the flink-agents installation this process is actually running.

    The probe is ``flink_agents.api.memory_object`` rather than the ``flink_agents``
    package. ``flink_agents/__init__.py`` is a single ``pkgutil.extend_path`` call, so
    ``flink_agents.__file__`` names whichever ``__init__.py`` came first on the path
    while ``flink_agents.api`` can be served from a different entry. ``api`` extends
    nothing, so a module under it pins the installation that supplied the code under
    test.
    """
    return {
        "flink_agents_api_file": memory_object_module.__file__,
        "flink_agents_version": _flink_agents_version(),
        "python_executable": sys.executable,
        "python_version": sys.version,
        "process_epoch": _PROCESS_EPOCH,
    }


def _write_runtime_identity(handshake_dir: str) -> None:
    """Publish the pre-kill identity so the harness can fail before it kills anything.

    This copy always describes the process that is about to be killed. The copy the
    assertions rely on rides in the verdict record, which the surviving process
    writes.
    """
    _atomic_write(
        Path(handshake_dir) / IDENTITY_MARKER,
        json.dumps(_runtime_identity(), sort_keys=True),
    )


def _mark_blob_written() -> None:
    global _BLOB_WRITES_IN_THIS_PROCESS
    _BLOB_WRITES_IN_THIS_PROCESS += 1


def _mark_tool_call_emitted() -> None:
    global _TOOL_CALLS_EMITTED_IN_THIS_PROCESS
    _TOOL_CALLS_EMITTED_IN_THIS_PROCESS += 1


def _blob_matches(raw: Any) -> bool:
    """Check the value is ``bytes`` or ``bytearray`` holding the known blob.

    The type gate is part of the assertion, not defensive coding. ``bytes()``
    accepts any iterable of ints, so without it a ``byte[]`` materialized as
    ``[0, 1, 102, ...]`` would compare equal to the blob and pass — and this
    predicate is the one whose bug produces a false pass.

    ``bytes`` is admitted because it is the only one of the two the memory value
    validator accepts at write time. ``bytearray`` is admitted because the value
    comes back across the bridge from a Java ``byte[]`` and may materialize as
    either — a read-side allowance this test makes, not one the write-side contract
    grants. Nothing else is admitted on the chance it might appear; anything else
    fails here rather than aborting the action, so the run still publishes a verdict
    and ``blob_observed_type`` records what did come back.
    """
    if not isinstance(raw, bytes | bytearray):
        return False
    return bytes(raw) == _KNOWN_BLOB


def _required_config(ctx: RunnerContext, key: str) -> str:
    value = ctx.config.get_str(key)
    if not value:
        msg = f"Missing config for the checkpoint recovery job: {key}"
        raise ValueError(msg)
    return value


class CheckpointRecoveryInput(BaseModel):
    """Input record for the checkpoint recovery agent.

    Attributes:
    ----------
    id : int
        Unique identifier used as the partition key.
    content : str
        The user message content fed to the agent.
    """

    id: int
    content: str


class CheckpointRecoveryKeySelector(KeySelector):
    """KeySelector extracting the partition key from a CheckpointRecoveryInput."""

    def get_key(self, value: CheckpointRecoveryInput) -> int:
        """Extract key from CheckpointRecoveryInput."""
        return value.id


class RecoveryMockChatConnection(BaseChatModelConnection):
    """Mock connection emitting one tool call, then joining the whole transcript."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[BaseTool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Request the blocking tool, or join every message once the tool replied.

        A non-``None`` ``output_schema`` is rejected: this connection has no native
        structured-output translation. Declaring the parameter keeps a caller-supplied
        schema out of ``**kwargs``.
        """
        self._reject_unsupported_output_schema(output_schema)
        if messages[-1].role == MessageRole.TOOL:
            # Joining every message carries the rebuilt transcript out to the
            # emitted content, which is where the assertion can reach it.
            content = "\n".join(message.content for message in messages)
            return ChatMessage(role=MessageRole.ASSISTANT, content=content)

        # Validate the tool was bound before the model was invoked.
        assert tools[0].name == BLOCKING_TOOL_NAME
        _mark_tool_call_emitted()
        tool_call = {
            "id": str(uuid.uuid4()),
            "type": ToolType.FUNCTION,
            "function": {"name": BLOCKING_TOOL_NAME, "arguments": {}},
        }
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=_ROUND_ONE_MARKER,
            tool_calls=[tool_call],
        )


class RecoveryMockChatModel(BaseChatModelSetup):
    """Mock chat model setup for the checkpoint recovery agent."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return model kwargs."""
        return {}


class CheckpointRecoveryAgent(Agent):
    """Agent held mid-tool-call so a checkpoint captures its memory."""

    @prompt
    @staticmethod
    def recovery_prompt() -> Prompt:
        """Prompt used by the mock chat model."""
        return Prompt.from_text(
            text="Please call the appropriate tool to do the following task: {task}",
        )

    @chat_model_connection
    @staticmethod
    def recovery_connection() -> ResourceDescriptor:
        """Chat model connection used by the mock chat model."""
        return ResourceDescriptor(
            clazz=f"{RecoveryMockChatConnection.__module__}."
            f"{RecoveryMockChatConnection.__name__}"
        )

    @chat_model_setup
    @staticmethod
    def recovery_chat_model() -> ResourceDescriptor:
        """Chat model referenced by the ChatRequestEvent."""
        return ResourceDescriptor(
            clazz=f"{RecoveryMockChatModel.__module__}."
            f"{RecoveryMockChatModel.__name__}",
            connection="recovery_connection",
            model="mock-model",
            prompt="recovery_prompt",
            tools=[BLOCKING_TOOL_NAME],
        )

    @tool(
        injected_args={
            "handshake_dir": InjectedArg.from_config(HANDSHAKE_DIR_CONFIG_KEY)
        }
    )
    @staticmethod
    def block_until_released(handshake_dir: str) -> str:
        """Hold the agent run open until the harness releases it.

        Takes no model-visible arguments, so the mock never has to fabricate one.
        The post-restore re-execution finds the release marker already present and
        returns immediately, which makes the handshake idempotent.

        Parameters
        ----------
        handshake_dir : str
            The handshake directory, injected by runtime.

        Returns:
        -------
        str:
            The sentinel, which reaches the transcript as the tool message and is
            required by the assertion. A timed-out handshake never gets here, so the
            transcript carries the framework's failure string instead.
        """
        await_release(handshake_dir)
        return _TOOL_SENTINEL

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Record the payload and start the tool-call round."""
        input_data = CheckpointRecoveryInput.model_validate(
            InputEvent.from_event(event).input
        )
        # Resolve both directories up front: a misspelled verdict_dir would otherwise
        # surface only after the whole park/kill/restart cycle has been paid for.
        handshake_dir = _required_config(ctx, HANDSHAKE_DIR_CONFIG_KEY)
        _required_config(ctx, VERDICT_DIR_CONFIG_KEY)
        _write_runtime_identity(handshake_dir)

        # Carry the record id across the chat round-trip via per-key memory,
        # since ChatResponseEvent does not echo the original input.
        ctx.short_term_memory.set("input_id", input_data.id)
        ctx.short_term_memory.set(_BLOB_MEMORY_KEY, _KNOWN_BLOB)
        _mark_blob_written()

        ctx.send_event(
            ChatRequestEvent(
                model="recovery_chat_model",
                messages=[
                    ChatMessage(role=MessageRole.USER, content=input_data.content)
                ],
                prompt_args={"task": input_data.content},
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Check the restored payload, publish the verdict, then fail on mismatch."""
        transcript = ChatResponseEvent.from_event(event).response.content
        input_id = ctx.short_term_memory.get("input_id")
        raw = ctx.short_term_memory.get(_BLOB_MEMORY_KEY)

        blob_ok = _blob_matches(raw)
        # _ROUND_ONE_MARKER is the load-bearing half: it exists only in the round-one
        # assistant message, so it can reach here only through the restored tool-call
        # context. USER_CONTENT is also restored, but weakly, because round two
        # re-renders the prompt from restored prompt_args and that rendering contains
        # it too.
        context_ok = _ROUND_ONE_MARKER in transcript and USER_CONTENT in transcript
        handshake_ok = _TOOL_SENTINEL in transcript
        restored_blob = blob_ok and _BLOB_WRITES_IN_THIS_PROCESS == 0
        restored_context = context_ok and _TOOL_CALLS_EMITTED_IN_THIS_PROCESS == 0
        passed = restored_blob and restored_context and handshake_ok
        # Identity is spread first so that no key it grows later can overwrite an
        # assertion field. The harness reads "verdict"; losing it to a silent
        # collision would be unrecoverable from the file alone.
        record = {
            **_runtime_identity(),
            "verdict": "pass" if passed else "fail",
            "input_id": input_id,
            "blob_ok": blob_ok,
            "restored_blob": restored_blob,
            "context_ok": context_ok,
            "restored_context": restored_context,
            "handshake_ok": handshake_ok,
            "blob_observed_type": type(raw).__name__,
            "blob_writes_in_this_process": _BLOB_WRITES_IN_THIS_PROCESS,
            "tool_calls_emitted_in_this_process": _TOOL_CALLS_EMITTED_IN_THIS_PROCESS,
            "transcript": transcript,
        }
        verdict = json.dumps(record, sort_keys=True)
        _atomic_write(
            Path(_required_config(ctx, VERDICT_DIR_CONFIG_KEY)) / VERDICT_MARKER,
            verdict,
        )

        if not passed:
            msg = f"checkpoint recovery assertions failed: {verdict}"
            raise AssertionError(msg)

        ctx.send_event(OutputEvent(output=verdict))
