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

from pydantic import BaseModel, ConfigDict, Field, model_validator
from typing_extensions import Annotated


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
    """A plain-text part of a ChatMessage."""

    type: Literal["text"] = "text"
    text: str = ""

    def __str__(self) -> str:
        return self.text


class MediaBlock(BaseModel):
    """Shared shape for binary media blocks: modality is the concrete type,
    encoding is the MIME type.

    The payload is carried by exactly one of base64 ``data`` or an externally
    managed ``url``. URL-backed content is externally managed: URLs may expire,
    may not be reachable by the model provider, and may be invalid after
    recovery from a checkpoint. The optional ``name``/``size_bytes``/``sha256``
    metadata also serves the Event Log, which records media metadata instead of
    payload bytes.
    """

    mime_type: str
    data: str | None = None  # base64; exactly one of data / url set
    url: str | None = None
    name: str | None = None
    size_bytes: int | None = None
    sha256: str | None = None

    @model_validator(mode="after")
    def _exactly_one_source(self) -> "MediaBlock":
        if (self.data is None) == (self.url is None):
            msg = "A media block carries exactly one of base64 data or a URL."
            raise ValueError(msg)
        return self

    def __str__(self) -> str:
        source = "inline" if self.data is not None else f"url={self.url}"
        return f"{type(self).__name__}({self.mime_type}, {source})"


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
