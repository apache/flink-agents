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
import json
import os
import uuid
from typing import Any, Dict, List, Literal, Mapping, Sequence
from urllib.parse import urlsplit

from dashscope import Generation, MultiModalConversation
from pydantic import Field
from typing_extensions import override

from flink_agents.api.agents.types import (
    OutputSchema,
    native_output_model,
    render_output_schema,
)
from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    ImageBlock,
    MessageRole,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    NativeStructuredOutputSupport,
)
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.tools.tool import Tool, ToolMetadata

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_MODEL = "qwen-plus"
_DashScopeTask = Literal["asr", "image_generation"]

# JSON Schema capability is checked against the selected interface. Snapshots in
# the same family can use different interfaces, so names are matched exactly.
# https://help.aliyun.com/zh/model-studio/qwen-structured-output
# https://help.aliyun.com/zh/model-studio/text-generation
_NATIVE_STRUCTURED_OUTPUT_MODELS = {
    "text": frozenset(
        {
            "qwen3.7-max",
            "qwen3.7-max-preview",
            "qwen3.7-max-2026-05-17",
            "qwen3.7-max-2026-05-20",
        }
    ),
    "multimodal": frozenset(
        {
            "qwen3.7-plus",
            "qwen3.7-plus-2026-05-26",
            "qwen3.7-flash",
            "qwen3.7-flash-2026-07-15",
            "qwen3.7-max-2026-06-08",
            "qwen3.8-max",
            "qwen3.8-max-0902",
            "qwen3.8-flash",
        }
    ),
}
# Match a family name or its hyphenated variants. Keep chat families specific:
# embedding, rerank, OCR and realtime models have different request contracts.
# https://help.aliyun.com/zh/model-studio/vision
# https://help.aliyun.com/zh/model-studio/text-generation
# https://help.aliyun.com/zh/model-studio/web-search
_MULTIMODAL_MODEL_FAMILIES = (
    "qwen-vl-plus",
    "qwen-vl-max",
    "qwen3-vl-plus",
    "qwen3-vl-flash",
    "qwen3.5-plus",
    "qwen3.5-flash",
    "qwen3.6-plus",
    "qwen3.6-flash",
    "qwen3.7-plus",
    "qwen3.7-flash",
    "qwen3.8-max",
    "qwen3.8-flash",
    "qvq",
)
# Size-based chat names start with a parameter size after these prefixes,
# e.g. qwen3-vl-8b-instruct or qwen3.8-2.4t-a95b. Requiring a leading digit
# avoids classifying qwen3-vl-embedding or qwen3.6-max-preview as MMC chat.
_MULTIMODAL_MODEL_SIZE_PREFIXES = (
    "qwen2-vl-",
    "qwen2.5-vl-",
    "qwen3-vl-",
    "qwen3.5-",
    "qwen3.6-",
    "qwen3.8-",
)
# The older Qwen3.7-Max snapshots use text generation; only this snapshot uses
# MMC. Kimi uses an exact name rather than assuming every Kimi model uses MMC.
# https://help.aliyun.com/en/model-studio/kimi-api
_MULTIMODAL_MODELS = frozenset({"qwen3.7-max-2026-06-08", "kimi-k2.5"})
_ASR_FORMAT_ALIASES = {
    "audio/mpeg": "mp3",
    "audio/x-ms-wma": "wma",
    "video/quicktime": "mov",
    "video/x-matroska": "mkv",
    "video/x-msvideo": "avi",
    "video/x-ms-wmv": "wmv",
}


def _resolve_api_type(
    api_type: str, model: str | None, *, task: _DashScopeTask | None = None
) -> str:
    """Resolve the interface for the task or chat model."""
    if api_type not in ("auto", "text", "multimodal"):
        msg = (
            f"Invalid DashScope api_type {api_type!r}; "
            "expected 'auto', 'text', or 'multimodal'."
        )
        raise ValueError(msg)
    if task is not None:
        return "multimodal"
    if api_type != "auto":
        return api_type
    if not isinstance(model, str):
        return "text"
    if (
        model in _MULTIMODAL_MODELS
        or any(
            model == family or model.startswith(f"{family}-")
            for family in _MULTIMODAL_MODEL_FAMILIES
        )
        or any(
            model.startswith(prefix) and model.removeprefix(prefix)[:1].isdigit()
            for prefix in _MULTIMODAL_MODEL_SIZE_PREFIXES
        )
    ):
        return "multimodal"
    return "text"


def _validate_asr_request(
    messages: Sequence[ChatMessage],
    tools: List[Tool] | None,
    output_schema: OutputSchema | None,
    response_format: Any,
) -> AudioBlock:
    """Return the single recording in a transcription request."""
    if tools or output_schema is not None or response_format is not None:
        msg = (
            "DashScope ASR only transcribes audio; tools, output_schema and "
            "response_format are not supported. Use a text model to process "
            "the transcription afterward."
        )
        raise ValueError(msg)
    if (
        len(messages) != 1
        or messages[0].role != MessageRole.USER
        or len(messages[0].blocks) != 1
        or not isinstance(messages[0].blocks[0], AudioBlock)
    ):
        msg = (
            "DashScope ASR requires one user message containing exactly one AudioBlock."
        )
        raise ValueError(msg)
    return messages[0].blocks[0]


def _validate_image_generation_request(
    messages: Sequence[ChatMessage],
    tools: List[Tool] | None,
    output_schema: OutputSchema | None,
    response_format: Any,
) -> None:
    """Require a single user prompt with optional reference images."""
    if tools or output_schema is not None or response_format is not None:
        msg = (
            "DashScope image generation and editing do not support tools, "
            "output_schema or response_format."
        )
        raise ValueError(msg)
    if len(messages) != 1 or messages[0].role != MessageRole.USER:
        msg = "DashScope image generation and editing require one user message."
        raise ValueError(msg)

    blocks = messages[0].blocks
    prompts = [block for block in blocks if isinstance(block, TextBlock)]
    if (
        len(prompts) != 1
        or not prompts[0].text.strip()
        or any(not isinstance(block, TextBlock | ImageBlock) for block in blocks)
    ):
        msg = (
            "DashScope image generation and editing require one non-empty text "
            "prompt and optional image blocks."
        )
        raise ValueError(msg)


def _generated_image_blocks(content: Any) -> list[ImageBlock]:
    """Decode the ordered PNG URLs returned by the synchronous image models."""
    msg = (
        "DashScope image generation must return a non-empty list of HTTP(S) image URLs."
    )
    if not isinstance(content, list) or not content:
        raise ValueError(msg)
    blocks = []
    for part in content:
        source = part.get("image") if isinstance(part, dict) else None
        if not isinstance(source, str) or not source:
            raise ValueError(msg)
        url = urlsplit(source)
        if url.scheme not in {"http", "https"} or not url.netloc:
            raise ValueError(msg)
        blocks.append(ImageBlock.from_url("image/png", source))
    return blocks


def _native_response_format(
    output_schema: OutputSchema | None,
) -> Dict[str, Any] | None:
    """Build the DashScope ``response_format`` for a native structured-output request.

    Returns ``None`` (leaving behavior unchanged) unless the schema is a ``BaseModel``
    subclass. A ``RowTypeInfo`` schema is skipped so it keeps the prompt-engineering
    fallback.

    Raises ``TypeError`` if a ``BaseModel`` schema cannot be rendered, naming the
    schema class rather than letting Pydantic's own error, which names only its
    internals, surface from a request the provider never sees. A schema that renders
    but declares no fields is sent as it is, leaving the provider to accept or refuse
    the document it receives.
    """
    model = native_output_model(output_schema)
    if model is None:
        return None
    return {
        "type": "json_schema",
        "json_schema": {
            "name": model.__name__,
            "strict": True,
            "schema": render_output_schema(model, lambda m: m.model_json_schema()),
        },
    }


def to_dashscope_tool(
    metadata: ToolMetadata,
    skip_length_check: bool = False,  # noqa:FBT001
) -> Dict[str, Any]:
    """To DashScope tool."""
    if not skip_length_check and len(metadata.description) > 1024:
        msg = (
            "Tool description exceeds maximum length of 1024 characters. "
            "Please shorten your description or move it to the prompt."
        )
        raise ValueError(msg)
    return {
        "type": "function",
        "function": {
            "name": metadata.name,
            "description": metadata.description,
            "parameters": metadata.get_parameters_dict(),
        },
    }


class _MultiModalConversation(MultiModalConversation):
    @classmethod
    @override
    def _preprocess_messages(
        cls, model: str, messages: List[Dict[str, Any]], api_key: str
    ) -> bool:
        # Media are already HTTP(S) URLs or inline data. SDK preprocessing also
        # treats text parts as local paths, which can change text or upload files.
        return False


class DashScopeChatModelConnection(BaseChatModelConnection):
    """Manage the connection to the DashScope API server.

    Attributes:
    ----------
    api_key : str
        Your DashScope API key.
    request_timeout : float
        The timeout for making http request to DashScope API server.
    """

    api_key: str = Field(
        default_factory=lambda: os.environ.get("DASHSCOPE_API_KEY"),
        description="Your DashScope API key.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to DashScope API server.",
    )

    def __init__(
        self,
        api_key: str | None = None,
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        resolved_api_key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not resolved_api_key:
            msg = (
                "DashScope API key is not provided. "
                "Please pass it as an argument or set the 'DASHSCOPE_API_KEY' environment variable."
            )
            raise ValueError(msg)

        super().__init__(
            api_key=resolved_api_key,
            request_timeout=request_timeout,
            **kwargs,
        )

    @override
    def supports_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> NativeStructuredOutputSupport:
        """``INFEASIBLE`` exactly when ``chat`` skips its native branch.

        Feasibility is the helper the native branch asks; capability only separates
        ``FEASIBLE`` from ``NATIVE_RECOMMENDED``, judged on the effective model and
        the selected interface. An omitted interface uses text generation.
        """
        if not self._can_apply_native_structured_output(
            output_schema, tools, model_kwargs
        ):
            return NativeStructuredOutputSupport.INFEASIBLE
        model = self._effective_model_for(model_kwargs)
        api_type = _resolve_api_type(
            (model_kwargs or {}).get("api_type", "text"), model
        )
        if self._model_supports_native_structured_output(model, api_type):
            return NativeStructuredOutputSupport.NATIVE_RECOMMENDED
        return NativeStructuredOutputSupport.FEASIBLE

    def _model_supports_native_structured_output(
        self, effective_model: str | None, api_type: str
    ) -> bool:
        """Whether the model supports JSON Schema on the resolved interface.

        An unlisted model/interface pair is not recommended for native output.
        This recommendation does not prevent a caller from sending a native schema.

        Args:
            effective_model: The model the request will be issued against, may be
                ``None``.
            api_type: The resolved interface, ``text`` or ``multimodal``.

        Returns:
            Whether native JSON Schema is recommended for this model/interface pair.
        """
        return effective_model in _NATIVE_STRUCTURED_OUTPUT_MODELS[api_type]

    def _effective_model_for(
        self, model_kwargs: Mapping[str, Any] | None
    ) -> str | None:
        """The ``model`` parameter, falling back to ``DEFAULT_MODEL`` when absent.

        ``chat`` resolves the model it calls the same way, so reading the parameter
        alone would answer ``None`` for every call that names no model, and report the
        default model incapable without ever asking about it.

        The fallback stands in for an absent parameter only, matching the request: a
        parameter that is present but empty is passed through, so the helper and the
        request agree on that input too.
        """
        if model_kwargs is None:
            return DEFAULT_MODEL
        return model_kwargs.get("model", DEFAULT_MODEL)

    def _can_apply_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> bool:
        """Whether a request built from these inputs would carry a native
        ``response_format``, leaving the effective model's capability out of the answer.

        Only a ``BaseModel`` subclass has a native translation here; a ``RowTypeInfo``
        wrapped in ``OutputSchema``, or no schema at all, has none and keeps the
        prompt-engineering fallback.

        A caller-supplied ``response_format`` is deliberately not a condition: the
        branch answers that conflict by raising rather than by skipping, so a caller
        that asked here first can still be met with an exception. Reporting the
        conflict infeasible instead would turn a documented error into a silently
        unconstrained request. Rendering raises likewise, on a ``BaseModel`` that
        carries no JSON Schema, so a ``True`` is not a promise the call succeeds.

        The ASR and image generation tasks bypass native schema rendering.
        For chat, bound tools can accompany a native schema.

        Parameters
        ----------
        output_schema : OutputSchema | None
            The schema the request would carry, or ``None`` for an unconstrained
            request.
        tools : List[Tool] | None
            Not read; bound tools do not stop this connection sending a native schema.
        model_kwargs : Mapping[str, Any] | None
            Used to determine the task.

        Returns:
        -------
        bool
            ``True`` if the task is chat and the schema wraps a ``BaseModel`` subclass.
        """
        return (model_kwargs or {}).get("task") is None and native_output_model(
            output_schema
        ) is not None

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Process a sequence of messages, and return a response.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
        output_schema : OutputSchema | None
            The schema the response should conform to, or ``None`` for an
            unconstrained response. Native structured output is applied for a
            ``BaseModel`` schema whatever the model; a ``RowTypeInfo`` schema keeps the
            prompt-engineering fallback. A ``response_format`` supplied alongside a
            schema is refused rather than resolved. ASR, image generation and image
            editing requests reject output schemas.
        **kwargs : Any
            ``api_type`` selects ``text`` (default), ``multimodal``, or ``auto``.
            Auto uses the multimodal interface for documented model names and text
            otherwise. Both interfaces accept text messages and tool calls. The
            multimodal interface accepts user images and video files from HTTP(S)
            URLs or Base64 sources. Set ``task="asr"`` with a Qwen-Audio-ASR model
            to transcribe one user AudioBlock through MMC. The audio format is
            inferred from the media type unless ``format`` is supplied. ASR does
            not accept tools or structured output.
            Set ``task="image_generation"`` with a Qwen-Image model to generate
            or edit images through MMC. This task accepts a single user prompt
            with optional image blocks and returns images. It does not accept
            tools or structured output. Media inputs in assistant or tool messages
            are unsupported. Multimodal calls must be non-streaming.
            Other parameters are passed to the model service.

        Returns:
        -------
        ChatResult
            Model response message.
        """
        # Check native schema feasibility against the original request arguments.
        raw_kwargs = dict(kwargs)

        dashscope_tools: List[Dict[str, Any]] | None = (
            [to_dashscope_tool(tool.metadata) for tool in tools] if tools else None
        )

        extract_reasoning = bool(kwargs.pop("extract_reasoning", False))

        req_api_key = kwargs.pop("api_key", self.api_key)

        task = kwargs.pop("task", None)
        if task not in (None, "asr", "image_generation"):
            msg = (
                f"Invalid DashScope task {task!r}; "
                "expected 'asr', 'image_generation', or None."
            )
            raise ValueError(msg)
        is_asr = task == "asr"
        is_image_generation = task == "image_generation"
        model_name = kwargs.pop("model", DEFAULT_MODEL)
        api_type = _resolve_api_type(
            kwargs.pop("api_type", "text"), model_name, task=task
        )
        if api_type == "multimodal" and kwargs.get("stream"):
            msg = "DashScope multimodal chat requires stream=False."
            raise ValueError(msg)
        if is_asr:
            audio = _validate_asr_request(
                messages, tools, output_schema, kwargs.get("response_format")
            )
            if kwargs.get("format") is None:
                media_type = audio.media_type.partition(";")[0].strip().lower()
                subtype = media_type.rpartition("/")[2].removeprefix("x-")
                kwargs["format"] = _ASR_FORMAT_ALIASES.get(media_type, subtype)

        dashscope_messages = self.__convert_to_dashscope_messages(
            messages, api_type, allow_audio=is_asr
        )
        if is_image_generation:
            _validate_image_generation_request(
                messages, tools, output_schema, kwargs.get("response_format")
            )
            content = dashscope_messages[0]["content"]
            if isinstance(content, str):
                dashscope_messages[0]["content"] = [{"text": content}]

        # The feasibility check is shared with the structured-output query rather than
        # restated, so the branch applies whenever the query answers other than
        # INFEASIBLE. Model capability is not asked: a caller that hands this
        # connection a schema has already chosen to send it. A payload with no native
        # translation is reported infeasible there, so it never reaches the conflict
        # test below and cannot raise over a response_format this branch was never
        # going to write.
        if self._can_apply_native_structured_output(output_schema, tools, raw_kwargs):
            # Tested before the schema is rendered, because a caller who supplies both
            # a schema and a response_format has a conflict to resolve whatever the
            # schema turns out to render to, and reporting a render failure instead
            # would describe the wrong problem. The name is read off the model class,
            # so this needs no rendered document.
            native_model = native_output_model(output_schema)
            if "response_format" in kwargs:
                msg = (
                    f"The {native_model.__name__} output schema is sent as "
                    f"response_format to model '{model_name}', so response_format "
                    f"must not also be passed as a kwarg. Remove that value, or "
                    f"omit output_schema to set response_format directly."
                )
                raise ValueError(msg)
            kwargs["response_format"] = _native_response_format(output_schema)

        api = Generation if api_type == "text" else _MultiModalConversation
        chat_options = (
            {} if is_asr else {"tools": dashscope_tools, "result_format": "message"}
        )
        response = api.call(
            model=model_name,
            messages=dashscope_messages,
            timeout=self.request_timeout,
            api_key=req_api_key,
            **chat_options,
            **kwargs,
        )

        if response.status_code != 200:
            msg = f"DashScope call failed: {response.message}"
            raise RuntimeError(msg)

        blocks = []
        if is_asr:
            text = (response.output or {}).get("text")
            if not isinstance(text, str):
                msg = "DashScope ASR response did not contain a transcript in output.text."
                raise RuntimeError(msg)
            blocks.append(TextBlock(text=text))
            finish_reason = response.output.get("finish_reason")
        else:
            choice = response.output["choices"][0]
            finish_reason = choice.get("finish_reason")
            message = choice["message"]
            if extract_reasoning and message.get("reasoning_content"):
                blocks.append(ReasoningBlock(text=message["reasoning_content"]))
            content = message.get("content")
            if is_image_generation:
                blocks.extend(_generated_image_blocks(content))
            elif api_type == "multimodal" and isinstance(content, list):
                for part in content:
                    if (
                        not isinstance(part, dict)
                        or set(part) != {"text"}
                        or not isinstance(part["text"], str)
                    ):
                        msg = "DashScope multimodal chat only supports text response content."
                        raise ValueError(msg)
                    blocks.append(TextBlock(text=part["text"]))
            elif content:
                blocks.append(TextBlock(text=content))
            for call in message.get("tool_calls") or []:
                function = call["function"]
                arguments = function.get("arguments") or {}
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                blocks.append(
                    ToolCallBlock(
                        call_id=call.get("id") or str(uuid.uuid4()),
                        name=function["name"],
                        input=arguments,
                    )
                )
        usage = response.usage
        token_usage = None
        # The SDK fills absent token counts with zeros when a task reports only
        # audio duration or image count.
        if usage and (task is None or usage.input_tokens or usage.output_tokens):
            token_usage = TokenUsage(
                prompt_tokens=usage.input_tokens,
                completion_tokens=usage.output_tokens,
            )
        return ChatResult(
            message=ChatMessage.assistant(blocks),
            model=str(model_name) if model_name is not None else None,
            usage=token_usage,
            finish_reason=finish_reason,
        )

    @staticmethod
    def __convert_to_dashscope_messages(
        messages: Sequence[ChatMessage],
        api_type: str,
        *,
        allow_audio: bool,
    ) -> List[Dict[str, Any]]:
        """Keep text-only messages as strings and user media as ordered parts."""
        provider = "DashScope"
        if api_type == "text":
            UnsupportedContentBlockError.reject_media(provider, messages)
        result = []
        for message in messages:
            blocks = (
                message.blocks[0].blocks
                if message.role == MessageRole.TOOL
                else message.blocks
            )
            content = []
            has_media = False
            for block in blocks:
                if isinstance(block, TextBlock):
                    content.append({"text": block.text})
                elif isinstance(block, ImageBlock | VideoBlock | AudioBlock):
                    if message.role != MessageRole.USER:
                        raise UnsupportedContentBlockError.for_block(
                            provider, block, "only user messages can carry media"
                        )
                    if isinstance(block, AudioBlock) and not allow_audio:
                        raise UnsupportedContentBlockError.for_block(
                            provider,
                            block,
                            "audio input requires task='asr'",
                        )
                    if isinstance(block.source, Base64Source):
                        source = f"data:{block.media_type};base64,{block.source.data}"
                    else:
                        source = block.source.url
                        if urlsplit(source).scheme not in {"http", "https"}:
                            raise UnsupportedContentBlockError.for_block(
                                provider,
                                block,
                                "media sources must be HTTP(S) URLs or Base64 data",
                            )
                    if isinstance(block, AudioBlock):
                        content.append(
                            {"type": "input_audio", "input_audio": {"data": source}}
                        )
                    else:
                        content.append({block.type: source})
                    has_media = True
                elif not isinstance(block, ReasoningBlock | ToolCallBlock):
                    raise UnsupportedContentBlockError.for_block(
                        provider, block, "unsupported message content"
                    )
            native = {
                "role": message.role.value,
                "content": content
                if has_media
                else "".join(p["text"] for p in content),
            }
            if message.tool_calls:
                native["tool_calls"] = [
                    {
                        "id": call.call_id,
                        "type": "function",
                        "function": {
                            "name": call.name,
                            "arguments": json.dumps(call.input),
                        },
                    }
                    for call in message.tool_calls
                ]
            if message.role == MessageRole.TOOL:
                native["tool_call_id"] = message.blocks[0].call_id
            result.append(native)
        return result


class DashScopeChatModelSetup(BaseChatModelSetup):
    """DashScope chat model setup which manages chat configuration and will internally
    call DashScope chat model connection to do chat.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    model : str
        Model name to use. Defaults to ``DEFAULT_MODEL`` when omitted via
        ``__init__``. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    temperature : float
        The temperature to use for sampling.
    additional_kwargs : Dict[str, Any]
        Additional model parameters for the DashScope API.
    extract_reasoning : bool
        If True, extracts reasoning content from the response and stores it
        in additional_kwargs.
    api_type : Literal["auto", "text", "multimodal"]
        Interface selection. Defaults to text generation. Auto selects the
        multimodal interface only for documented model names.
    task : Literal["asr", "image_generation"] | None
        Select synchronous transcription or image generation and editing through MMC.
    """

    temperature: float = Field(
        default=0.7,
        description="The temperature to use for sampling.",
        ge=0.0,
        le=2.0,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional model parameters for the DashScope API.",
    )
    extract_reasoning: bool = Field(
        default=False,
        description="If True, extracts reasoning content from the response and stores it.",
    )
    api_type: Literal["auto", "text", "multimodal"] = Field(
        default="text",
        description="DashScope interface: text (default), multimodal, or auto.",
    )
    task: _DashScopeTask | None = Field(
        default=None,
        description="Select 'asr' for transcription or 'image_generation' for images.",
    )

    def __init__(
        self,
        connection: str,
        model: str = DEFAULT_MODEL,
        temperature: float = 0.7,
        additional_kwargs: Dict[str, Any] | None = None,
        extract_reasoning: bool | None = False,
        api_type: Literal["auto", "text", "multimodal"] = "text",
        task: _DashScopeTask | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            temperature=temperature,
            additional_kwargs=additional_kwargs,
            extract_reasoning=extract_reasoning,
            api_type=api_type,
            task=task,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return DashScope model configuration."""
        base_kwargs = {
            "model": self.model,
            "temperature": self.temperature,
            "extract_reasoning": self.extract_reasoning,
            "api_type": self.api_type,
        }
        if self.task is not None:
            base_kwargs["task"] = self.task
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }

    @override
    def _validate_structured_output_request(self) -> None:
        task = self.model_kwargs.get("task")
        if task in ("asr", "image_generation"):
            msg = f"DashScope task {task!r} does not support output_schema."
            raise ValueError(msg)
