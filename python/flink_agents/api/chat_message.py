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

from pydantic import BaseModel, ConfigDict, Field
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
    data: str = Field(min_length=1)

    @property
    def size_bytes(self) -> int:
        """The decoded byte count implied by the base64 length."""
        padding = 2 if self.data.endswith("==") else 1 if self.data.endswith("=") else 0
        return len(self.data) * 3 // 4 - padding

    def __str__(self) -> str:
        return f"Base64Source({self.size_bytes} bytes)"


class UrlSource(BaseModel):
    """An externally managed media location: a URL or a provider file URI.

    The location is externally managed: it may expire, may not be reachable by
    the model provider, and may be invalid after recovery from a checkpoint.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    type: Literal["url"] = "url"
    url: str = Field(min_length=1)

    def __str__(self) -> str:
        return f"UrlSource({self.url})"


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
    media_type: str = Field(min_length=1)
    source: MediaSource
    name: str | None = None
    size_bytes: int | None = None
    sha256: str | None = None

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


ContentBlock = Annotated[
    TextBlock | ImageBlock | AudioBlock | VideoBlock | DocumentBlock,
    Field(discriminator="type"),
]


def _blocks_of(text: str) -> List[ContentBlock]:
    """An empty text becomes an empty block list rather than an empty text block."""
    return [TextBlock(text=text)] if text else []


class ChatMessage(BaseModel):
    """Chat message.

    ChatMessages are the inputs and outputs of ChatModels.

    Attributes:
    ----------
    role : MessageRole
        The message productor or purpose.
    blocks : List[ContentBlock]
        The ordered, typed content of the message; a text-only message carries
        a single TextBlock.
    tool_calls: List[Dict[str, Any]]
        The tools call information.
    extra_args : dict[str, Any]
        Additional information about the message.
    """

    # Unknown keys fail loudly: the replaced `content` field would otherwise be
    # silently ignored, producing an empty message instead of an error.
    model_config = ConfigDict(extra="forbid")

    role: MessageRole = MessageRole.USER
    blocks: List[ContentBlock] = Field(default_factory=list)
    tool_calls: List[Dict[str, Any]] = Field(default_factory=list)
    extra_args: Dict[str, Any] = Field(default_factory=dict)

    @property
    def text(self) -> str:
        """The text projection: the ordered concatenation of the TextBlocks."""
        return "".join(
            block.text for block in self.blocks if isinstance(block, TextBlock)
        )

    def set_text(self, text: str) -> None:
        """Replace the content with a single text block (empty text clears it)."""
        self.blocks = _blocks_of(text)

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
    def tool(
        cls, content: str | Sequence[ContentBlock], **kwargs: Any
    ) -> "ChatMessage":
        """Create a TOOL message from text or content blocks."""
        return cls.of(MessageRole.TOOL, content, **kwargs)

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
