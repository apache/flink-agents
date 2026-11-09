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
import asyncio
import os
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

import pytest
from dashscope import Generation, MultiModalConversation
from dashscope.api_entities.dashscope_response import (
    DashScopeAPIResponse,
    MultiModalConversationResponse,
)
from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import (
    AudioBlock,
    ChatMessage,
    ImageBlock,
    MessageRole,
    TextBlock,
    ToolResultBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.api.chat_result import TokenUsage
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.chat_models.dashscope_chat_model import (
    DEFAULT_MODEL,
    DashScopeChatModelConnection,
    DashScopeChatModelSetup,
)
from flink_agents.plan.actions.chat_model_action import (
    process_chat_request_or_tool_response,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

test_model = os.environ.get("DASHSCOPE_CHAT_MODEL", "qwen-plus")
api_key_available = "DASHSCOPE_API_KEY" in os.environ

_AUDIO_URL = "https://example.invalid/recording.mp3"
_IMAGE_URL = "https://example.invalid/image.png"


@pytest.mark.integration
@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_dashscope_chat() -> None:
    """Test basic chat functionality of DashScopeChatModelConnection."""
    connection = DashScopeChatModelConnection()
    response = connection.chat(
        [ChatMessage.of(MessageRole.USER, "Hello!")], model=test_model
    )
    assert response is not None
    assert response.text is not None
    assert response.text.strip() != ""
    assert response.message.role == MessageRole.ASSISTANT


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b.

    Parameters
    ----------
    a : int
        The first operand
    b : int
        The second operand

    Returns:
    -------
    int:
        The sum of a and b
    """
    return a + b


def get_tool(name: str, type: ResourceType) -> FunctionTool:
    """Helper function to create a tool for testing."""
    return FunctionTool(func=PythonFunction.from_callable(add))


@pytest.mark.integration
@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_dashscope_chat_with_tools() -> None:
    """Test chat functionality with tool calling."""
    connection = DashScopeChatModelConnection()

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.TOOL:
            return get_tool(name=name, type=ResourceType.TOOL)
        else:
            return connection

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    llm = DashScopeChatModelSetup(
        model=test_model,
        connection="dashscope",
        tools=["add"],
        resource_context=mock_ctx,
    )

    llm.open()

    response = llm.chat(
        [
            ChatMessage.of(
                MessageRole.USER,
                "Could you help me calculate the sum of 1 and 2?",
            )
        ]
    )

    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call.input) == 3


def test_dashscope_chat_with_extract_reasoning(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test that extract_reasoning functionality works correctly (mock DashScope)."""
    content = "The meaning of life is often considered to be 42, according to the Hitchhiker's Guide to the Galaxy."
    reasoning_content = (
        "To answer what the meaning of life is, I should consider philosophical perspectives. "
        "The question is often associated with the number 42 from Hitchhiker's Guide to the Galaxy."
    )

    mocked_response = SimpleNamespace(
        status_code=200,
        output={
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": content,
                        "reasoning_content": reasoning_content,
                        "tool_calls": None,
                    }
                }
            ]
        },
        usage=SimpleNamespace(input_tokens=100, output_tokens=50),
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.dashscope_chat_model.Generation.call",
        mock_call,
    )

    connection = DashScopeChatModelConnection(
        api_key=os.environ.get("DASHSCOPE_API_KEY", "fake-key"),
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        return connection

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    llm = DashScopeChatModelSetup(
        model=test_model,
        connection="dashscope",
        extract_reasoning=True,
        resource_context=mock_ctx,
    )

    llm.open()

    response = llm.chat(
        [ChatMessage.of(MessageRole.USER, "What's the meaning of life?")]
    )

    mock_call.assert_called_once()

    assert (
        response.text
        == "The meaning of life is often considered to be 42, according to the Hitchhiker's Guide to the Galaxy."
    )
    reasoning = next(
        block.text for block in response.message.blocks if block.type == "reasoning"
    )
    assert "philosophical perspectives" in reasoning
    assert "Hitchhiker's Guide to the Galaxy" in reasoning


def test_model_field_roundtrip() -> None:
    """Verify `model` is preserved through pydantic dump/validate round-trip."""
    setup = DashScopeChatModelSetup(connection="conn", model="test-model")
    restored = DashScopeChatModelSetup.model_validate(setup.model_dump())
    assert restored.model == "test-model"


def test_default_model_when_omitted() -> None:
    """Verify per-integration default applies when `model` is omitted from __init__."""
    setup = DashScopeChatModelSetup(connection="conn")
    assert setup.model == DEFAULT_MODEL


def test_tool_call_ids_round_trip(monkeypatch: pytest.MonkeyPatch) -> None:
    """The DashScope call id reaches both the assistant call and the tool result."""
    tool_call_response = SimpleNamespace(
        status_code=200,
        output={
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call_abc",
                                "type": "function",
                                "function": {
                                    "name": "add",
                                    "arguments": '{"a": 1, "b": 2}',
                                },
                            }
                        ],
                    }
                }
            ]
        },
        usage=SimpleNamespace(input_tokens=10, output_tokens=5),
    )
    mock_call = MagicMock(return_value=tool_call_response)
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.dashscope_chat_model.Generation.call",
        mock_call,
    )
    connection = DashScopeChatModelConnection(api_key="fake-key")

    response = connection.chat(
        [ChatMessage.of(MessageRole.USER, "What is 1 + 2?")], model=test_model
    )
    # The tool result uses the same call ID as the model's tool call.
    assert response.tool_calls[0].call_id == "call_abc"

    tool_result = ChatMessage.tool(
        ToolResultBlock(
            call_id="call_abc",
            blocks=[TextBlock(text="3")],
        )
    )
    connection.chat(
        [
            ChatMessage.of(MessageRole.USER, "What is 1 + 2?"),
            response.message,
            tool_result,
        ],
        model=test_model,
    )

    sent = mock_call.call_args.kwargs["messages"]
    assert sent[1]["tool_calls"][0]["id"] == "call_abc"
    assert sent[2] == {"role": "tool", "content": "3", "tool_call_id": "call_abc"}


def _open_setup(**options: Any) -> DashScopeChatModelSetup:
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource.return_value = DashScopeChatModelConnection(api_key="fake-key")
    setup = DashScopeChatModelSetup(
        connection="dashscope", resource_context=ctx, **options
    )
    setup.open()
    return setup


def _multimodal_response(
    output: dict[str, Any], usage: dict[str, int] | None = None
) -> MultiModalConversationResponse:
    return MultiModalConversationResponse.from_api_response(
        DashScopeAPIResponse(status_code=200, output=output, usage=usage)
    )


def _assistant_output(content: str | list[dict[str, str]]) -> dict[str, Any]:
    return {"choices": [{"message": {"role": "assistant", "content": content}}]}


@pytest.fixture
def chat_calls(monkeypatch: pytest.MonkeyPatch) -> dict[str, MagicMock]:
    text_call = MagicMock(
        return_value=SimpleNamespace(
            status_code=200,
            output=_assistant_output("text response"),
            usage=None,
        )
    )
    multimodal_call = MagicMock(
        return_value=_multimodal_response(
            _assistant_output([{"text": "multimodal response"}])
        )
    )
    monkeypatch.setattr(Generation, "call", text_call)
    monkeypatch.setattr(MultiModalConversation, "call", multimodal_call)
    return {"text": text_call, "multimodal": multimodal_call}


@pytest.mark.parametrize(
    ("model", "options", "interface"),
    [
        ("qwen3-vl-plus", {}, "text"),
        ("custom-vision-model", {"api_type": "multimodal"}, "multimodal"),
        ("qwen3-vl-plus", {"api_type": "auto"}, "multimodal"),
        ("qwen-plus", {"api_type": "auto"}, "text"),
    ],
)
def test_dashscope_chat_selects_interface(
    chat_calls: dict[str, MagicMock],
    model: str,
    options: dict[str, str],
    interface: str,
) -> None:
    setup = _open_setup(model=model, **options)

    result = setup.chat([ChatMessage.user("Hello")])

    assert result.text == f"{interface} response"
    chat_calls[interface].assert_called_once()
    assert chat_calls[interface].call_args.kwargs["model"] == model
    assert sum(call.call_count for call in chat_calls.values()) == 1


def test_dashscope_multimodal_chat_preserves_user_content_order(
    chat_calls: dict[str, MagicMock],
) -> None:
    setup = _open_setup(model="qwen3-vl-plus", api_type="multimodal")
    image = ImageBlock.from_url("image/png", _IMAGE_URL)
    video = VideoBlock.from_base64("video/mp4", "dmlkZW8=")

    result = setup.chat(
        [ChatMessage.user([image, TextBlock(text="Compare these."), video])]
    )

    assert result.text == "multimodal response"
    assert chat_calls["multimodal"].call_args.kwargs["messages"] == [
        {
            "role": "user",
            "content": [
                {"image": _IMAGE_URL},
                {"text": "Compare these."},
                {"video": "data:video/mp4;base64,dmlkZW8="},
            ],
        }
    ]


@pytest.mark.parametrize(
    ("role", "api_type", "error"),
    [
        (MessageRole.USER, "text", "sends text only"),
        (MessageRole.ASSISTANT, "multimodal", "only user messages can carry media"),
    ],
)
def test_dashscope_chat_rejects_unsupported_media_before_request(
    chat_calls: dict[str, MagicMock], role: MessageRole, api_type: str, error: str
) -> None:
    setup = _open_setup(model="qwen3-vl-plus", api_type=api_type)
    image = ImageBlock.from_url("image/png", _IMAGE_URL)

    with pytest.raises(UnsupportedContentBlockError, match=error):
        setup.chat([ChatMessage.of(role, [image])])

    chat_calls["text"].assert_not_called()
    chat_calls["multimodal"].assert_not_called()


@pytest.mark.parametrize(
    ("model", "audio", "encoded_audio", "usage", "expected_usage"),
    [
        (
            "qwen-audio-3.1-asr-flash",
            AudioBlock.from_url("audio/mpeg", _AUDIO_URL),
            _AUDIO_URL,
            {"input_tokens": 9, "output_tokens": 4},
            TokenUsage(prompt_tokens=9, completion_tokens=4),
        ),
        (
            "qwen-audio-3.0-asr-flash",
            AudioBlock.from_base64("audio/mpeg", "YXVkaW8="),
            "data:audio/mpeg;base64,YXVkaW8=",
            {"duration": 1},
            None,
        ),
    ],
)
def test_dashscope_asr_transcribes_one_recording(
    chat_calls: dict[str, MagicMock],
    model: str,
    audio: AudioBlock,
    encoded_audio: str,
    usage: dict[str, int],
    expected_usage: TokenUsage | None,
) -> None:
    setup = _open_setup(model=model, task="asr")
    multimodal_call = chat_calls["multimodal"]
    multimodal_call.return_value = _multimodal_response({"text": "Hello."}, usage)

    result = setup.chat([ChatMessage.user([audio])])

    assert result.blocks == (TextBlock(text="Hello."),)
    assert result.usage == expected_usage
    chat_calls["text"].assert_not_called()
    multimodal_call.assert_called_once()
    request = multimodal_call.call_args.kwargs
    assert request["format"] == "mp3"
    assert request["messages"] == [
        {
            "role": "user",
            "content": [
                {"type": "input_audio", "input_audio": {"data": encoded_audio}}
            ],
        }
    ]


def test_dashscope_image_generation_can_edit_returned_image(
    chat_calls: dict[str, MagicMock],
) -> None:
    setup = _open_setup(model="qwen-image-3.0", task="image_generation")
    generated_url = "https://example.invalid/generated.png"
    edited_url = "https://example.invalid/edited.png"
    multimodal_call = chat_calls["multimodal"]
    multimodal_call.side_effect = [
        _multimodal_response(
            _assistant_output([{"image": url}]),
            {"image_count": 1},
        )
        for url in (generated_url, edited_url)
    ]

    generated = setup.chat([ChatMessage.user("Draw a circle.")])
    edited = setup.chat(
        [ChatMessage.user([generated.blocks[0], TextBlock(text="Make it blue.")])]
    )

    assert generated.blocks == (ImageBlock.from_url("image/png", generated_url),)
    assert edited.blocks == (ImageBlock.from_url("image/png", edited_url),)
    assert generated.usage is None
    assert edited.usage is None
    chat_calls["text"].assert_not_called()
    assert [call.kwargs["messages"] for call in multimodal_call.call_args_list] == [
        [{"role": "user", "content": [{"text": "Draw a circle."}]}],
        [
            {
                "role": "user",
                "content": [{"image": generated_url}, {"text": "Make it blue."}],
            }
        ],
    ]


class _StructuredResponse(BaseModel):
    text: str


@pytest.mark.parametrize(
    ("task", "message", "error"),
    [
        (
            "asr",
            ChatMessage.user(
                [
                    TextBlock(text="Transcribe"),
                    AudioBlock.from_url("audio/mpeg", _AUDIO_URL),
                ]
            ),
            "exactly one AudioBlock",
        ),
        (
            "image_generation",
            ChatMessage.user([ImageBlock.from_url("image/png", _IMAGE_URL)]),
            "one non-empty text prompt",
        ),
    ],
)
def test_dashscope_task_rejects_invalid_input_before_request(
    chat_calls: dict[str, MagicMock], task: str, message: ChatMessage, error: str
) -> None:
    setup = _open_setup(task=task)

    with pytest.raises(ValueError, match=error):
        setup.chat([message])

    chat_calls["text"].assert_not_called()
    chat_calls["multimodal"].assert_not_called()


@pytest.mark.parametrize(
    ("model", "task", "message"),
    [
        (
            "qwen-audio-3.1-asr-flash",
            "asr",
            ChatMessage.user([AudioBlock.from_url("audio/mpeg", _AUDIO_URL)]),
        ),
        ("qwen-image-3.0", "image_generation", ChatMessage.user("Draw a circle.")),
    ],
)
def test_dashscope_task_rejects_output_schema_in_chat_event(
    chat_calls: dict[str, MagicMock], model: str, task: str, message: ChatMessage
) -> None:
    setup = _open_setup(model=model, task=task)
    ctx = MagicMock()
    ctx.has_resource.return_value = False
    ctx.get_resource.return_value = setup
    ctx.sensory_memory.get.return_value = None
    request = ChatRequestEvent(
        model="dashscope",
        messages=[message],
        output_schema=OutputSchema(output_schema=_StructuredResponse),
    )

    asyncio.run(process_chat_request_or_tool_response(request, ctx))

    ctx.send_event.assert_called_once()
    response = ctx.send_event.call_args.args[0]
    assert isinstance(response, ChatResponseEvent)
    assert response.request_id == request.id
    assert response.status == ChatResponseEvent.FAILED
    assert f"task {task!r} does not support output_schema" in response.error
    chat_calls["text"].assert_not_called()
    chat_calls["multimodal"].assert_not_called()
