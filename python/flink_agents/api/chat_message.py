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
from enum import Enum
from typing import Any, Dict, List, Literal, Sequence

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictStr,
    field_validator,
    model_validator,
)
from typing_extensions import Annotated, Self


class MessageRole(str, Enum):
    """Message role.

    Attributes:
    ----------
    SYSTEM : str
        Used to tell the chat model how to behave and provide additional context.
    USER : str
        Represents input from a user interacting with the model.
    ASSISTANT : str
        Represents a response from the model, which can include text or a
        request to invoke tools.
    TOOL : str
        A message used to pass the results of a tools invocation back to the model.
    """

    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


class TextBlock(BaseModel):
    """A plain-text, immutable part of a ChatMessage."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    type: Literal["text"] = "text"
    text: str = ""

    def __str__(self) -> str:
        return self.text


class Base64Source(BaseModel):
    """An inline media payload, carried as base64 text."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    type: Literal["base64"] = "base64"
    data: StrictStr = Field(min_length=1, repr=False)

    @property
    def size_bytes(self) -> int:
        """Infer the byte count assuming valid standard Base64, without decoding."""
        padding = 2 if self.data.endswith("==") else 1 if self.data.endswith("=") else 0
        return max(0, len(self.data) * 3 // 4 - padding)

    def __str__(self) -> str:
        return f"Base64Source({self.size_bytes} bytes)"


class UrlSource(BaseModel):
    """An externally managed media location: a URL or a provider file URI.

    The location is externally managed: it may expire, may not be reachable by
    the model provider, and may be invalid after recovery from a checkpoint.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    type: Literal["url"] = "url"
    url: StrictStr = Field(min_length=1, repr=False)

    def __str__(self) -> str:
        return "UrlSource(<redacted>)"


MediaSource = Annotated[
    Base64Source | UrlSource,
    Field(discriminator="type"),
]
"""Where a MediaBlock's payload lives: a discriminated, immutable value.

The kind of source is structural rather than a validation rule over nullable
fields; a managed blob/reference source can be added later without touching
the block shape. Providers explicitly convert or reject the source kinds they
support.
"""


class MediaBlock(BaseModel):
    """Shared shape for binary media blocks: modality is the concrete type,
    encoding is the media type (RFC 6838; historically called a MIME type), and
    the payload location is a typed ``source``.

    Media blocks are immutable. The optional ``name``/``size_bytes``/``sha256``
    metadata also serves the Event Log, which records media metadata instead of
    payload bytes.
    """

    # Frozen keeps sharing a block (e.g. across a routing context copy) safe,
    # and matches the validated immutable construction on the Java side.
    # extra="forbid" is set on every nested model because ChatMessage's own
    # setting does not propagate: an unknown field must fail here, as it does
    # in Java, rather than being silently dropped.
    model_config = ConfigDict(frozen=True, extra="forbid")

    # min_length mirrors the Java constructor: both languages reject an empty
    # media type, so a block valid here is valid after crossing the bridge.
    media_type: StrictStr = Field(min_length=1)
    source: MediaSource
    name: StrictStr | None = None
    size_bytes: Annotated[int, Field(strict=True, ge=0, le=2**63 - 1)] | None = None
    sha256: StrictStr | None = None

    @classmethod
    def from_base64(cls, media_type: str, data: str, **kwargs: Any) -> Self:
        """Create a block carrying an inline base64 payload."""
        return cls(media_type=media_type, source=Base64Source(data=data), **kwargs)

    @classmethod
    def from_url(cls, media_type: str, url: str, **kwargs: Any) -> Self:
        """Create a block referencing an externally managed URL or file URI."""
        return cls(media_type=media_type, source=UrlSource(url=url), **kwargs)

    def __str__(self) -> str:
        return f"{type(self).__name__}({self.media_type}, {self.source})"


class ImageBlock(MediaBlock):
    """The image content of a ChatMessage — see MediaBlock for the media shape."""

    type: Literal["image"] = "image"


class AudioBlock(MediaBlock):
    """The audio content of a ChatMessage — see MediaBlock for the media shape."""

    type: Literal["audio"] = "audio"


class VideoBlock(MediaBlock):
    """The video content of a ChatMessage — see MediaBlock for the media shape."""

    type: Literal["video"] = "video"


class DocumentBlock(MediaBlock):
    """The document content of a ChatMessage — see MediaBlock for the media shape."""

    type: Literal["document"] = "document"


class ReasoningBlock(BaseModel):
    """Provider reasoning, excluded from the ordinary text projection.

    Metadata preserves provider-specific continuation data. Adapters must not
    implicitly turn reasoning into assistant text or send it to another provider.
    """

    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)
    type: Literal["reasoning"] = "reasoning"
    text: str | None = None
    metadata: Dict[str, Any] = Field(default_factory=dict, repr=False)


class ToolCallBlock(BaseModel):
    """One call, identified within its tool request (not globally)."""

    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)
    type: Literal["tool_call"] = "tool_call"
    call_id: StrictStr = Field(min_length=1)
    name: StrictStr = Field(min_length=1)
    input: Dict[str, Any] = Field(default_factory=dict)
    metadata: Dict[str, Any] = Field(default_factory=dict, repr=False)


class ToolResultBlock(BaseModel):
    """The model-facing result of a call; execution data belongs to ToolResponse."""

    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)
    type: Literal["tool_result"] = "tool_result"
    call_id: StrictStr = Field(min_length=1)
    blocks: tuple["ContentBlock", ...] = ()
    is_error: bool = False
    metadata: Dict[str, Any] = Field(default_factory=dict, repr=False)

    @field_validator("blocks")
    @classmethod
    def validate_result_blocks(cls, blocks: tuple) -> tuple:
        """Tool output may contain only text and media."""
        if any(not isinstance(b, TextBlock | MediaBlock) for b in blocks):
            msg = "Tool results may contain only text and media blocks"
            raise ValueError(msg)
        return blocks

    @property
    def text(self) -> str:
        """Concatenate result text without stringifying media."""
        return "".join(b.text for b in self.blocks if isinstance(b, TextBlock))


ContentBlock = Annotated[
    TextBlock
    | ImageBlock
    | AudioBlock
    | VideoBlock
    | DocumentBlock
    | ReasoningBlock
    | ToolCallBlock
    | ToolResultBlock,
    Field(discriminator="type"),
]
ToolResultBlock.model_rebuild()


class UnsupportedContentBlockError(ValueError):
    """A chat model integration cannot send a content block to its provider.

    Raised for a media type the provider does not accept, or a media block in
    a message role that only takes text, rather than silently dropping or
    converting the block. Messages name the block type, media type and source
    type only, never the media payload or URL.
    """

    @classmethod
    def for_block(
        cls, provider: str, block: ContentBlock, reason: str
    ) -> "UnsupportedContentBlockError":
        """Create the error for a block the provider cannot send.

        The message reads "{provider} cannot send a(n) {type} block ({media type},
        {source type} source): {reason}."
        """
        article = "an" if block.type[0] in "aeiou" else "a"
        description = f"{article} {block.type} block"
        if isinstance(block, MediaBlock):
            description += f" ({block.media_type}, {block.source.type} source)"
        return cls(f"{provider} cannot send {description}: {reason}.")


def _blocks_of(text: str) -> List[ContentBlock]:
    """An empty text becomes an empty block list rather than an empty text block."""
    return [TextBlock(text=text)] if text else []


class ChatMessage(BaseModel):
    """Chat message.

    ChatMessages represent conversation history and are the inputs to ChatModels.
    A model returns its assistant message as part of a ChatResult.

    Attributes:
    ----------
    role : MessageRole
        The message source or purpose.
    blocks : tuple[ContentBlock, ...]
        The ordered, typed content of the message, including text, media,
        reasoning, tool calls, or tool results as allowed by the role.
        A TOOL message contains exactly one ToolResultBlock.
    metadata : dict[str, Any]
        Additional information about the message, such as provider-specific
        attributes.
    text : str
        Read-only concatenation of the top-level text blocks, excluding
        reasoning and text nested inside tool results.
    tool_calls : tuple[ToolCallBlock, ...]
        Read-only view of the tool calls in blocks, preserving their order.
    """

    model_config = ConfigDict(extra="forbid", frozen=True, validate_default=True)
    role: MessageRole
    blocks: tuple[ContentBlock, ...]
    metadata: Dict[str, Any] = Field(default_factory=dict, repr=False)

    @model_validator(mode="after")
    def validate_content(self) -> Self:
        """Validate roles and call IDs before dispatch, including after recovery."""
        if self.role == MessageRole.TOOL:
            if len(self.blocks) != 1 or not isinstance(self.blocks[0], ToolResultBlock):
                msg = "A TOOL message requires exactly one ToolResultBlock"
                raise ValueError(msg)
        else:
            for block in self.blocks:
                if isinstance(block, ToolResultBlock):
                    msg = "ToolResultBlock requires the TOOL role"
                    raise ValueError(msg)  # noqa: TRY004 - Pydantic validation failure
                if self.role == MessageRole.SYSTEM and not isinstance(block, TextBlock):
                    msg = "SYSTEM messages accept only text"
                    raise ValueError(msg)
                if self.role == MessageRole.USER and not isinstance(
                    block, TextBlock | MediaBlock
                ):
                    msg = "USER messages accept only text and media"
                    raise ValueError(msg)
        ids = [b.call_id for b in self.tool_calls]
        if len(set(ids)) != len(ids):
            msg = "Duplicate tool call ID in one message"
            raise ValueError(msg)
        return self

    @property
    def tool_calls(self) -> tuple[ToolCallBlock, ...]:
        """Read-only projection; only blocks are serialized."""
        return tuple(b for b in self.blocks if isinstance(b, ToolCallBlock))

    def with_blocks(self, blocks: Sequence[ContentBlock]) -> "ChatMessage":
        """Create a validated message with replacement content."""
        return ChatMessage(role=self.role, blocks=blocks, metadata=self.metadata)

    @property
    def text(self) -> str:
        """The text projection: the ordered concatenation of the TextBlocks."""
        return "".join(
            block.text for block in self.blocks if isinstance(block, TextBlock)
        )

    @classmethod
    def user(
        cls, content: str | Sequence[ContentBlock], **kwargs: Any
    ) -> "ChatMessage":
        """Create a USER message from text or content blocks."""
        return cls.of(MessageRole.USER, content, **kwargs)

    @classmethod
    def system(
        cls, content: str | Sequence[ContentBlock], **kwargs: Any
    ) -> "ChatMessage":
        """Create a SYSTEM message from text or content blocks."""
        return cls.of(MessageRole.SYSTEM, content, **kwargs)

    @classmethod
    def assistant(
        cls, content: str | Sequence[ContentBlock], **kwargs: Any
    ) -> "ChatMessage":
        """Create an ASSISTANT message from text or content blocks."""
        return cls.of(MessageRole.ASSISTANT, content, **kwargs)

    @classmethod
    def tool(cls, result: ToolResultBlock, **kwargs: Any) -> "ChatMessage":
        """Create a TOOL message for one completed call."""
        return cls(role=MessageRole.TOOL, blocks=[result], **kwargs)

    @classmethod
    def of(
        cls,
        role: MessageRole,
        content: str | Sequence[ContentBlock],
        **kwargs: Any,
    ) -> "ChatMessage":
        """Create a message with the given role from text or content blocks."""
        blocks = _blocks_of(content) if isinstance(content, str) else list(content)
        return cls(role=role, blocks=blocks, **kwargs)

    def __str__(self) -> str:
        return f"{self.role.value}: {self.text}"


def find_first_system_message(messages: List[ChatMessage]) -> int:
    """Helper method to find the index of the first system message."""
    for i in range(len(messages)):
        if messages[i].role == MessageRole.SYSTEM:
            return i
    return -1
