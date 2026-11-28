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
import json
import os
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import httpx
import pytest
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
from flink_agents.integrations.tests.dashscope_http import (
    record_dashscope_requests as record_dashscope_requests,
)
from flink_agents.plan.actions.chat_model_action import (
    process_chat_request_or_tool_response,
)
from flink_agents.plan.configuration import AgentConfiguration
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


def test_dashscope_chat_with_extract_reasoning(http_calls) -> None:
    """Test that extract_reasoning functionality works correctly (mock DashScope)."""
    content = "The meaning of life is often considered to be 42, according to the Hitchhiker's Guide to the Galaxy."
    reasoning_content = (
        "To answer what the meaning of life is, I should consider philosophical perspectives. "
        "The question is often associated with the number 42 from Hitchhiker's Guide to the Galaxy."
    )

    requests, responses = http_calls
    responses.append(
        _response(
            {
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
            {"input_tokens": 100, "output_tokens": 50},
        )
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

    assert len(requests) == 1

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


@pytest.mark.parametrize(
    ("api_type", "model"), [("text", test_model), ("multimodal", "qwen3-vl-plus")]
)
def test_tool_call_ids_round_trip(http_calls, api_type: str, model: str) -> None:
    """The DashScope call ID reaches both the assistant call and the tool result."""
    requests, responses = http_calls
    tool_call_response = _response(
        {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": [{"type": "text", "text": ""}]
                        if api_type == "multimodal"
                        else "",
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
        {"input_tokens": 10, "output_tokens": 5},
    )
    responses.extend([tool_call_response, _response(_assistant_output("Done."))])
    connection = DashScopeChatModelConnection(api_key="fake-key")
    prompt = "What is 1 + 2?"
    message = ChatMessage.user(prompt)
    if api_type == "multimodal":
        message = ChatMessage.user(
            [ImageBlock.from_url("image/png", _IMAGE_URL), TextBlock(text=prompt)]
        )

    response = connection.chat([message], model=model, api_type=api_type)
    assert response.tool_calls[0].input == {"a": 1, "b": 2}
    # The tool result uses the same call ID as the model's tool call.
    assert response.tool_calls[0].call_id == "call_abc"

    tool_result = ChatMessage.tool(
        ToolResultBlock(
            call_id="call_abc",
            blocks=[TextBlock(text="3")],
        )
    )
    connection.chat(
        [message, response.message, tool_result], model=model, api_type=api_type
    )

    sent = json.loads(requests[-1].content)["input"]["messages"]
    assert sent[1]["tool_calls"][0]["id"] == "call_abc"
    assert sent[2] == {"role": "tool", "content": "3", "tool_call_id": "call_abc"}


@pytest.mark.parametrize(
    "arguments",
    [
        pytest.param({}, id="missing"),
        pytest.param({"arguments": ""}, id="empty-string"),
        pytest.param({"arguments": "{"}, id="incomplete-json"),
        pytest.param({"arguments": "[]"}, id="non-object"),
        pytest.param({"arguments": "{}"}, id="no-parameters"),
    ],
)
def test_tool_arguments_require_an_explicit_json_object(http_calls, arguments) -> None:
    _, responses = http_calls
    output = _assistant_output("")
    output["choices"][0]["message"]["tool_calls"] = [
        {"id": "call_1", "function": {"name": "now", **arguments}}
    ]
    responses.append(_response(output))
    setup = _open_setup()
    messages = [ChatMessage.user("What time is it?")]
    if arguments.get("arguments") == "{}":
        assert setup.chat(messages).tool_calls[0].input == {}
    else:
        with pytest.raises(ValueError, match="now.*arguments"):
            setup.chat(messages)


def _open_setup(**options: Any) -> DashScopeChatModelSetup:
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource.return_value = DashScopeChatModelConnection(api_key="fake-key")
    setup = DashScopeChatModelSetup(
        connection="dashscope", resource_context=ctx, **options
    )
    setup.open()
    return setup


def _response(
    output: dict[str, Any], usage: dict[str, int] | None = None
) -> httpx.Response:
    body = {"output": output}
    if usage is not None:
        body["usage"] = usage
    return httpx.Response(200, json=body)


def _assistant_output(content: str | list[dict[str, str]]) -> dict[str, Any]:
    return {"choices": [{"message": {"role": "assistant", "content": content}}]}


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
    http_calls,
    model: str,
    options: dict[str, str],
    interface: str,
) -> None:
    requests, responses = http_calls
    responses.append(_response(_assistant_output("ok")))
    setup = _open_setup(model=model, **options)

    result = setup.chat([ChatMessage.user("Hello")])

    assert result.text == "ok"
    assert len(requests) == 1
    assert requests[0].url.path.endswith(f"/{interface}-generation/generation")
    assert json.loads(requests[0].content)["model"] == model


@pytest.mark.parametrize("task", [None, "asr"])
def test_result_format_text_applies_only_to_asr(http_calls, task) -> None:
    requests, responses = http_calls
    if task == "asr":
        message = ChatMessage.user([AudioBlock.from_url("audio/mpeg", _AUDIO_URL)])
        output = {"text": "Hello"}
    else:
        message = ChatMessage.user("Hello")
        output = _assistant_output("Hello")
    responses.append(_response(output))

    result = _open_setup(task=task).chat([message], result_format="text")

    assert result.text == "Hello"
    expected = "text" if task == "asr" else "message"
    assert json.loads(requests[0].content)["parameters"]["result_format"] == expected


def test_text_chat_sends_the_full_conversation_in_order(http_calls) -> None:
    requests, responses = http_calls
    responses.append(_response(_assistant_output("Blue")))
    messages = [
        ChatMessage.user("Remember blue."),
        ChatMessage.assistant("OK"),
        ChatMessage.user("Which color?"),
    ]

    _open_setup().chat(messages)

    assert json.loads(requests[0].content)["input"] == {
        "messages": [
            {"role": "user", "content": "Remember blue."},
            {"role": "assistant", "content": "OK"},
            {"role": "user", "content": "Which color?"},
        ]
    }


def test_multimodal_chat_forwards_model_parameters(http_calls) -> None:
    requests, responses = http_calls
    responses.append(_response(_assistant_output("ok")))
    parameters = {"voice": "Cherry", "text": "spoken text", "language_type": "Chinese"}
    setup = _open_setup(api_type="multimodal", additional_kwargs=parameters)

    setup.chat([ChatMessage.user("Hello")])

    body = json.loads(requests[0].content)
    assert set(body["input"]) == {"messages"}
    assert {name: body["parameters"][name] for name in parameters} == parameters


@pytest.mark.parametrize(
    ("usage", "expected"),
    [
        (None, None),
        ({"input_tokens": 12}, TokenUsage(prompt_tokens=12)),
        (
            {"input_tokens": 12, "output_tokens": 0},
            TokenUsage(prompt_tokens=12, completion_tokens=0),
        ),
    ],
)
def test_chat_usage_reports_only_measured_tokens(http_calls, usage, expected) -> None:
    _, responses = http_calls
    responses.append(_response(_assistant_output("hello"), usage))
    result = _open_setup().chat([ChatMessage.user("Hello")])
    assert result.usage == expected


def test_dashscope_multimodal_chat_preserves_user_content_order(
    http_calls,
) -> None:
    requests, responses = http_calls
    responses.append(
        _response(_assistant_output([{"text": "multimodal "}, {"text": "response"}]))
    )
    setup = _open_setup(model="qwen3-vl-plus", api_type="multimodal")
    image = ImageBlock.from_url("image/png", _IMAGE_URL)
    video = VideoBlock.from_base64("video/mp4", "dmlkZW8=")

    result = setup.chat(
        [ChatMessage.user([image, TextBlock(text="Compare these."), video])]
    )

    assert result.text == "multimodal response"
    assert result.blocks == (TextBlock(text="multimodal response"),)
    assert json.loads(requests[0].content)["input"]["messages"] == [
        {
            "role": "user",
            "content": [
                {"image": _IMAGE_URL},
                {"text": "Compare these."},
                {"video": "data:video/mp4;base64,dmlkZW8="},
            ],
        }
    ]


def test_dashscope_multimodal_chat_keeps_local_path_text_at_sdk_boundary(
    http_calls, tmp_path: Path
) -> None:
    local_file = tmp_path / "fixture.txt"
    local_file.write_text("This file must stay local.", encoding="utf-8")
    requests, responses = http_calls
    responses.append(_response(_assistant_output([{"text": "response"}])))
    setup = _open_setup(model="qwen3-vl-plus", api_type="multimodal")
    image = ImageBlock.from_url("image/png", _IMAGE_URL)

    result = setup.chat([ChatMessage.user([image, TextBlock(text=str(local_file))])])

    assert result.text == "response"
    assert len(requests) == 1
    assert requests[0].url.path.endswith("/multimodal-generation/generation")
    assert json.loads(requests[0].content)["input"]["messages"][0]["content"] == [
        {"image": _IMAGE_URL},
        {"text": str(local_file)},
    ]


@pytest.mark.parametrize(
    ("role", "api_type", "error"),
    [
        (
            MessageRole.USER,
            "text",
            r"the text interface accepts only text.*api_type='multimodal'",
        ),
        (MessageRole.TOOL, "text", "the text interface accepts only text"),
        (MessageRole.USER, "auto", "the text interface accepts only text"),
        (MessageRole.ASSISTANT, "multimodal", "only user messages can carry media"),
    ],
)
def test_dashscope_chat_rejects_unsupported_media_before_request(
    http_calls, role: MessageRole, api_type: str, error: str
) -> None:
    model = "qwen-plus" if api_type == "auto" else "qwen3-vl-plus"
    setup = _open_setup(model=model, api_type=api_type)
    image = ImageBlock.from_url("image/png", _IMAGE_URL)
    message = (
        ChatMessage.tool(ToolResultBlock(call_id="call", blocks=[image]))
        if role == MessageRole.TOOL
        else ChatMessage.of(role, [image])
    )

    with pytest.raises(UnsupportedContentBlockError, match=error):
        setup.chat([message])

    assert not http_calls[0]


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
    http_calls,
    model: str,
    audio: AudioBlock,
    encoded_audio: str,
    usage: dict[str, int],
    expected_usage: TokenUsage | None,
) -> None:
    setup = _open_setup(model=model, task="asr")
    requests, responses = http_calls
    responses.append(_response({"text": "Hello."}, usage))

    result = setup.chat([ChatMessage.user([audio])])

    assert result.blocks == (TextBlock(text="Hello."),)
    assert result.usage == expected_usage
    assert len(requests) == 1
    assert requests[0].url.path.endswith("/multimodal-generation/generation")
    request = json.loads(requests[0].content)
    assert request["parameters"]["format"] == "mp3"
    assert request["parameters"]["result_format"] == "message"
    assert request["input"]["messages"] == [
        {
            "role": "user",
            "content": [{"audio": encoded_audio}],
        }
    ]


@pytest.mark.parametrize(
    ("output", "has_transcript"),
    [
        ({"text": ""}, True),
        (_assistant_output([{"text": ""}]), True),
        (_assistant_output([{"type": "text", "text": ""}]), True),
        ({}, False),
        (_assistant_output([]), False),
        (_assistant_output([{"image": _IMAGE_URL}]), False),
        (_assistant_output([{"text": "speech"}, {"image": _IMAGE_URL}]), False),
    ],
)
def test_asr_preserves_silence_and_rejects_a_missing_transcript(
    http_calls, output, has_transcript
) -> None:
    requests, responses = http_calls
    responses.append(_response(output, {"input_tokens": 0, "output_tokens": 0}))
    setup = _open_setup(model="qwen-audio-3.1-asr-flash", task="asr")
    messages = [ChatMessage.user([AudioBlock.from_url("audio/mpeg", _AUDIO_URL)])]
    if not has_transcript:
        with pytest.raises(RuntimeError, match="transcript"):
            setup.chat(messages)
    else:
        result = setup.chat(messages)
        assert result.blocks == (TextBlock(text=""),)
        assert result.usage == TokenUsage(prompt_tokens=0, completion_tokens=0)
    assert len(requests) == 1


def test_dashscope_image_generation_can_edit_returned_image(
    http_calls,
) -> None:
    setup = _open_setup(
        model="qwen-image-3.0",
        task="image_generation",
        additional_kwargs={"size": "1024*1024", "n": 2},
    )
    generated_url = "https://example.invalid/generated.png"
    edited_url = "https://example.invalid/edited.png"
    requests, responses = http_calls
    responses.extend(
        _response(
            _assistant_output([{"type": "image", "image": url}]),
            {"image_count": 1},
        )
        for url in (generated_url, edited_url)
    )

    generated = setup.chat(
        [ChatMessage.user("Draw a circle.")],
        n=1,
        request_timeout=2.5,
        headers={"x-example": "once"},
        api_key="one-call-key",
    )
    edited = setup.chat(
        [ChatMessage.user([generated.blocks[0], TextBlock(text="Make it blue.")])]
    )

    first, second = requests
    assert first.headers["authorization"] == "Bearer one-call-key"
    assert first.headers["x-example"] == "once"
    assert set(first.extensions["timeout"].values()) == {2.5}
    assert "x-example" not in second.headers
    assert second.headers["authorization"] == "Bearer fake-key"
    assert set(second.extensions["timeout"].values()) == {60.0}
    assert json.loads(first.content)["parameters"] == {
        "size": "1024*1024",
        "n": 1,
        "result_format": "message",
        "temperature": 0.7,
    }
    assert json.loads(second.content)["parameters"]["n"] == 2
    assert generated.blocks == (ImageBlock.from_url("image/png", generated_url),)
    assert edited.blocks == (ImageBlock.from_url("image/png", edited_url),)
    assert generated.usage is None
    assert edited.usage is None
    assert all(
        r.url.path.endswith("/multimodal-generation/generation") for r in requests
    )
    assert [json.loads(r.content)["input"]["messages"] for r in requests] == [
        [{"role": "user", "content": [{"text": "Draw a circle."}]}],
        [
            {
                "role": "user",
                "content": [{"image": generated_url}, {"text": "Make it blue."}],
            }
        ],
    ]


@pytest.mark.parametrize("caption", [None, "same-part", "separate-part"])
def test_image_generation_requires_and_preserves_ordered_images(
    http_calls, caption
) -> None:
    first = "https://example.invalid/first.png"
    second = "http://example.invalid/second.png"
    parts = [
        {"type": "image", "image": first},
        {"text": ""},
        {"image": second},
        {"type": "text", "text": ""},
    ]
    if caption == "same-part":
        parts[0]["text"] = "Caption"
    elif caption == "separate-part":
        parts.insert(1, {"text": "Separate caption"})
    output = _assistant_output(parts)
    output["choices"][0]["finish_reason"] = "stop"
    usage = {"input_tokens": 6, "image_count": 2}
    requests, responses = http_calls
    responses.append(_response(output, usage))

    setup = _open_setup(model="qwen-image-3.0", task="image_generation")
    messages = [ChatMessage.user("Generate two pictures.")]
    if caption is not None:
        with pytest.raises(RuntimeError, match=r"HTTP\(S\) image URLs"):
            setup.chat(messages)
    else:
        result = setup.chat(messages)
        assert result.blocks == tuple(
            ImageBlock.from_url("image/png", url) for url in (first, second)
        )
        assert result.usage == TokenUsage(prompt_tokens=6)
        assert result.finish_reason == "stop"
    assert len(requests) == 1


@pytest.mark.parametrize(
    ("model", "task", "content", "error"),
    [
        (
            "qwen-image-3.0",
            "image_generation",
            [],
            "must return a non-empty list",
        ),
        (
            "qwen-image-3.0",
            "image_generation",
            [{"text": ""}],
            "must return a non-empty list",
        ),
        (
            "qwen3-vl-plus",
            None,
            [{"text": "Description"}, {"image": _IMAGE_URL}],
            "only supports text response content",
        ),
    ],
)
def test_dashscope_chat_reports_invalid_multimodal_response(
    http_calls,
    model: str,
    task: str | None,
    content: list[dict[str, str]],
    error: str,
) -> None:
    setup = _open_setup(model=model, task=task, api_type="multimodal")
    requests, responses = http_calls
    responses.append(_response(_assistant_output(content)))

    with pytest.raises(RuntimeError, match=error):
        setup.chat([ChatMessage.user("Draw a circle.")])

    assert len(requests) == 1


@pytest.mark.parametrize(
    ("task", "user_message"),
    [
        (None, ChatMessage.user("Hello")),
        ("image_generation", ChatMessage.user("Draw a circle.")),
        ("asr", ChatMessage.user([AudioBlock.from_url("audio/mpeg", _AUDIO_URL)])),
    ],
)
def test_chat_service_errors_preserve_message_and_cause(
    http_calls, task, user_message
) -> None:
    requests, responses = http_calls
    responses.append(
        httpx.Response(
            429,
            json={"code": "Throttling", "message": "Slow down", "request_id": "req-1"},
        )
    )
    with pytest.raises(RuntimeError, match="DashScope call failed: .*Slow down") as err:
        _open_setup(task=task).chat([user_message])
    assert err.value.__cause__.status_code == 429
    assert err.value.__cause__.request_id == "req-1"
    assert len(requests) == 1


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
        (
            "image_generation",
            ChatMessage.user(
                [
                    TextBlock(text="Edit this."),
                    AudioBlock.from_url("audio/mpeg", _AUDIO_URL),
                ]
            ),
            "one non-empty text prompt and optional image blocks",
        ),
    ],
)
def test_dashscope_task_rejects_invalid_input_before_request(
    http_calls, task: str, message: ChatMessage, error: str
) -> None:
    setup = _open_setup(task=task)

    with pytest.raises(ValueError, match=error):
        setup.chat([message])

    assert not http_calls[0]


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
    http_calls, model: str, task: str, message: ChatMessage
) -> None:
    setup = _open_setup(model=model, task=task)
    ctx = MagicMock()
    ctx.config = AgentConfiguration()
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
    assert not http_calls[0]


@pytest.mark.parametrize(
    ("api_type", "model", "option", "value"),
    [
        ("text", "qwen-plus", "api_protocol", "websocket"),
        ("text", "qwen-plus", "timeout", 2.5),
        ("multimodal", "qwen3-vl-plus", "extra_headers", {}),
        ("text", "qwen-plus", "extra_body", {}),
        ("text", "qwen-plus", "raw", True),
        ("multimodal", "qwen3-vl-plus", "session", object()),
        ("text", "qwen-plus", "history", [{"user": "hi", "bot": "hello"}]),
        ("text", "qwen-plus", "prompt", "hi"),
        ("text", "qwen-plus", "plugins", {"test": {}}),
        ("text", "bailian-v1", "customized_model_id", "my-model"),
        ("multimodal", "qwen3-vl-plus", "history", []),
    ],
)
def test_unsupported_sdk_option_fails_before_chat_request(
    http_calls, api_type, model, option, value
) -> None:
    setup = _open_setup(
        model=model, api_type=api_type, additional_kwargs={option: value}
    )
    with pytest.raises(ValueError, match=option):
        setup.chat([ChatMessage.user("hello")])
    assert not http_calls[0]


@pytest.mark.parametrize("api_type", ["text", "multimodal"])
@pytest.mark.parametrize(
    ("connection_options", "call_options", "expected"),
    [
        ({}, {}, 60.0),
        ({"request_timeout": 1.25}, {}, 1.25),
        ({"request_timeout": 1.25}, {"request_timeout": 2.5}, 2.5),
    ],
)
def test_request_timeout_reaches_sdk_transport(
    http_calls,
    api_type: str,
    connection_options: dict,
    call_options: dict,
    expected: float,
) -> None:
    connection = DashScopeChatModelConnection(api_key="fake-key", **connection_options)
    requests, responses = http_calls
    responses.append(_response(_assistant_output("ok")))
    connection.chat([ChatMessage.user("Hello")], api_type=api_type, **call_options)
    assert set(requests[0].extensions["timeout"].values()) == {expected}
    connection.close()
