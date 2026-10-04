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
################################################################################
import array
import base64
import sys
from collections.abc import Mapping
from typing import Any, Dict, Sequence, cast

from openai import NOT_GIVEN, OpenAI
from pydantic import Field, field_validator
from typing_extensions import override

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingResult,
    EmbeddingTokenUsage,
)

DEFAULT_REQUEST_TIMEOUT = 30.0
DEFAULT_BASE_URL = "https://api.openai.com/v1"
DEFAULT_MAX_RETRIES = 3
DEFAULT_ENCODING_FORMAT = "float"
# Upper bounds shared with the OpenAI chat models and the Java connection.
MAX_OPENAI_TIMEOUT_SECONDS = 2_147_483.647
MAX_OPENAI_RETRIES = 2_147_483_647
MAX_DIMENSIONS = 2_147_483_647  # int range, as the Java connection requires
ENCODING_FORMATS = frozenset({"float", "base64"})
# Typed request fields; additional_kwargs may not repeat them.
RESERVED_ADDITIONAL_KWARGS = frozenset(
    {"model", "input", "encoding_format", "dimensions", "user"}
)


def _blank_to_none(value: Any, name: str) -> str | None:
    """Require a string or None; blank strings are treated as absent, as in Java."""
    if value is None:
        return None
    if not isinstance(value, str):
        msg = f"{name} must be a string, got: {type(value).__name__}"
        raise TypeError(msg)
    return value if value.strip() else None


def _check_additional_kwargs(additional_kwargs: Any) -> Dict[str, Any]:
    """Require a mapping with non-blank string keys and no typed request field."""
    if not isinstance(additional_kwargs, Mapping):
        msg = (
            f"additional_kwargs must be a map, got: {type(additional_kwargs).__name__}"
        )
        raise TypeError(msg)
    # Per-call keys are stringified as Java's Map keys are (the setup field already
    # requires string keys); null and blank keys are rejected in both languages.
    if any(key is None or not str(key).strip() for key in additional_kwargs):
        msg = "additional_kwargs contains an empty key."
        raise ValueError(msg)
    additional_kwargs = {str(key): value for key, value in additional_kwargs.items()}
    collisions = sorted(RESERVED_ADDITIONAL_KWARGS & additional_kwargs.keys())
    if collisions:
        msg = (
            f"additional_kwargs must not contain the typed request fields {collisions}; "
            "set them through the corresponding setup argument instead."
        )
        raise ValueError(msg)
    return additional_kwargs


def _check_encoding_format(encoding_format: Any) -> str:
    """Validate encoding_format; a blank value means the default, as in Java."""
    if _blank_to_none(encoding_format, "encoding_format") is None:
        return DEFAULT_ENCODING_FORMAT
    if encoding_format not in ENCODING_FORMATS:
        msg = (
            f"encoding_format must be one of {sorted(ENCODING_FORMATS)}, "
            f"got: {encoding_format!r}"
        )
        raise ValueError(msg)
    return encoding_format


def _check_model(model: Any) -> str:
    if not isinstance(model, str) or not model.strip():
        msg = "OpenAI embedding requires a non-empty 'model' (setup or per-call parameter)."
        raise ValueError(msg)
    return model


def _check_dimensions(dimensions: Any) -> int | None:
    """Accept a positive integer (an integral float counts, as in Java)."""
    if dimensions is None:
        return None
    if isinstance(dimensions, float) and dimensions.is_integer():
        dimensions = int(dimensions)
    if (
        isinstance(dimensions, bool)
        or not isinstance(dimensions, int)
        or not 0 < dimensions <= MAX_DIMENSIONS
    ):
        msg = f"dimensions must be a positive integer, got: {dimensions!r}"
        raise ValueError(msg)
    return dimensions


def _usage_count(value: Any) -> int | None:
    """A token count, or None when a compatible server sends something unusable."""
    if isinstance(value, str) and value.strip().isdecimal():
        return int(value)
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return None


def _decode_embedding(value: Any, *, base64_requested: bool) -> list[float]:
    # With encoding_format="base64" the SDK hands the base64 string through untouched.
    if isinstance(value, str):
        if not base64_requested:
            msg = "a string vector was returned although encoding_format was float"
            raise TypeError(msg)
        # validate=True so corrupted input raises instead of being silently truncated.
        # Padding is restored only for a completely unpadded value, which Java's
        # decoder accepts too; partial padding such as "AACAPw=" stays invalid, as it
        # is for Java.
        padded = value if "=" in value else value + "=" * (-len(value) % 4)
        floats = array.array("f", base64.b64decode(padded, validate=True))
        if sys.byteorder == "big":
            floats.byteswap()  # the wire format is little-endian float32
        return floats.tolist()
    vector = list(value)
    if any(isinstance(x, bool) or not isinstance(x, int | float) for x in vector):
        msg = "embedding contains non-numeric values"
        raise TypeError(msg)
    return vector


def _order_embeddings(
    data: Sequence[Any], expected: int, *, base64_requested: bool
) -> list[list[float]]:
    """Place each vector at its response ``index``; mirrors the Java connection.

    An item without an index keeps its response position. Every slot must be
    filled exactly once, so out-of-range, duplicated or contradictory indices
    are rejected rather than silently mapped.
    """
    if len(data) != expected:
        msg = f"OpenAI returned {len(data)} embeddings for {expected} input texts."
        raise RuntimeError(msg)
    ordered: list[list[float] | None] = [None] * expected
    for position, item in enumerate(data):
        if item is None:
            msg = f"OpenAI returned a malformed embedding at position {position}."
            raise RuntimeError(msg)
        index = getattr(item, "index", None)
        if isinstance(index, float) and index.is_integer():
            index = int(index)  # an integral float counts, as Jackson does in Java
        if index is None:
            target = position
        elif isinstance(index, int) and not isinstance(index, bool):
            target = index
        else:
            # A present but non-integer index is malformed, not absent.
            msg = (
                f"OpenAI returned a non-integer embedding index {index!r} "
                f"at position {position}."
            )
            raise RuntimeError(msg)
        if not 0 <= target < expected or ordered[target] is not None:
            msg = (
                f"OpenAI returned an unexpected embedding index {target} "
                f"at position {position}."
            )
            raise RuntimeError(msg)
        try:
            ordered[target] = _decode_embedding(
                item.embedding, base64_requested=base64_requested
            )
        except (TypeError, ValueError) as e:
            msg = f"OpenAI returned a malformed embedding at position {position}: {e}"
            raise RuntimeError(msg) from e
    # The size and duplicate checks above guarantee every slot is filled.
    return cast("list[list[float]]", ordered)


class OpenAIEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """OpenAI Embedding Model Connection which manages connection to OpenAI API.

    Visit https://platform.openai.com/ to get your API key.

    Attributes:
    ----------
    api_key : str
        OpenAI API key for authentication.
    base_url : str
        Base URL for the OpenAI API (default: https://api.openai.com/v1).
    request_timeout : float
        The timeout for making HTTP requests to OpenAI API. Set to 0 to disable
        timeouts.
    max_retries : int
        Maximum number of retries for failed requests.
    organization : Optional[str]
        Optional organization ID for API requests.
    project : Optional[str]
        Optional project ID for API requests.
    """

    api_key: str = Field(description="OpenAI API key for authentication.")

    # A null (YAML ``~``) or blank base_url means the default, as in Java.
    @field_validator("base_url", mode="before")
    @classmethod
    def _default_base_url(cls, value: Any) -> Any:
        try:
            return (
                DEFAULT_BASE_URL if _blank_to_none(value, "base_url") is None else value
            )
        except TypeError as e:  # pydantic only converts ValueError
            raise ValueError(str(e)) from e

    # A YAML null means the default, as parseRequestTimeout(null) does in Java.
    @field_validator("request_timeout", mode="before")
    @classmethod
    def _default_request_timeout(cls, value: Any) -> Any:
        return DEFAULT_REQUEST_TIMEOUT if value is None else value

    @field_validator("max_retries", mode="before")
    @classmethod
    def _default_max_retries(cls, value: Any) -> Any:
        return DEFAULT_MAX_RETRIES if value is None else value

    base_url: str = Field(
        default=DEFAULT_BASE_URL,
        description="Base URL for the OpenAI API.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making HTTP requests to OpenAI API. "
        "Set to 0 to disable timeouts.",
        ge=0,
        le=MAX_OPENAI_TIMEOUT_SECONDS,
        allow_inf_nan=False,
    )
    max_retries: int = Field(
        default=DEFAULT_MAX_RETRIES,
        description="Maximum number of retries for failed requests.",
        ge=0,
        le=MAX_OPENAI_RETRIES,
    )
    organization: str | None = Field(
        default=None,
        description="Optional organization ID for API requests.",
    )
    project: str | None = Field(
        default=None,
        description="Optional project ID for API requests.",
    )

    def __init__(
        self,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
        organization: str | None = None,
        project: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            api_key=api_key,
            base_url=base_url,
            request_timeout=request_timeout,
            max_retries=max_retries,
            organization=organization,
            project=project,
            **kwargs,
        )

    __client: OpenAI | None = None

    @property
    def client(self) -> OpenAI:
        """Return OpenAI client."""
        if self.__client is None:
            self.__client = OpenAI(
                api_key=self.api_key,
                # Blank strings mean the default / unset (the SDK may then read its
                # OPENAI_ORG_ID / OPENAI_PROJECT_ID environment variables).
                base_url=_blank_to_none(self.base_url, "base_url") or DEFAULT_BASE_URL,
                timeout=None if self.request_timeout == 0 else self.request_timeout,
                organization=_blank_to_none(self.organization, "organization"),
                project=_blank_to_none(self.project, "project"),
                max_retries=self.max_retries,
            )
        return self.__client

    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for a single text query."""
        return self.embed_with_usage(text, **kwargs).embeddings

    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings and return OpenAI token usage when available.

        ``additional_kwargs`` (from the setup) are sent as extra request body
        properties; they may not repeat the typed fields, and ``None`` values are
        omitted. Per-call keyword arguments override the setup's entry by entry, so
        a per-call ``additional_kwargs`` map replaces the setup's map. Other
        keyword arguments are ignored, as in the Java connection. Vectors are
        returned in input order and ``base64`` responses are decoded.
        """
        # Extract OpenAI specific parameters
        model = _check_model(kwargs.pop("model", None))
        encoding_format = _check_encoding_format(kwargs.pop("encoding_format", None))
        dimensions = _check_dimensions(kwargs.pop("dimensions", None))
        user = _blank_to_none(kwargs.pop("user", None), "user")
        raw_additional_kwargs = kwargs.pop("additional_kwargs", None)
        additional_kwargs = _check_additional_kwargs(
            {} if raw_additional_kwargs is None else raw_additional_kwargs
        )
        extra_body = {k: v for k, v in additional_kwargs.items() if v is not None}

        if text is None:
            msg = "text must be a string or a sequence of strings, got: None"
            raise TypeError(msg)
        if not isinstance(text, str):
            if isinstance(text, Mapping | set | frozenset):
                msg = f"text must be a string or a sequence of strings, got: {type(text).__name__}"
                raise TypeError(msg)
            text = list(text)  # materialize one-shot iterables before validating
            for position, item in enumerate(text):
                if not isinstance(item, str):
                    msg = f"Text at index {position} is not a string; every input must be a string."
                    raise TypeError(msg)
            if len(text) == 0:
                return EmbeddingResult(embeddings=[], token_usage=None)

        # Create the embedding request
        # The resolved format is always sent, as the Java connection does; a "base64"
        # response is decoded below.
        response = self.client.embeddings.create(
            model=model,
            input=text,
            encoding_format=encoding_format,
            dimensions=dimensions if dimensions is not None else NOT_GIVEN,
            user=user if user is not None else NOT_GIVEN,
            extra_body=extra_body or None,
        )

        usage = getattr(response, "usage", None)
        token_usage = None
        if usage is not None:
            prompt_tokens = _usage_count(getattr(usage, "prompt_tokens", None))
            total_tokens = _usage_count(getattr(usage, "total_tokens", None))
            # Embedding requests have no completion tokens, so a missing side of the
            # usage equals the other side (as the Tongyi connection does).
            if prompt_tokens is None:
                prompt_tokens = total_tokens
            if prompt_tokens is not None:
                token_usage = EmbeddingTokenUsage(
                    prompt_tokens=int(prompt_tokens),
                    total_tokens=int(
                        total_tokens if total_tokens is not None else prompt_tokens
                    ),
                )

        # A compatible server may answer 200 with no ``data``; report it as a short
        # response rather than a bare TypeError.
        embeddings = _order_embeddings(
            getattr(response, "data", None) or [],
            1 if isinstance(text, str) else len(text),
            base64_requested=encoding_format == "base64",
        )
        return EmbeddingResult(
            embeddings=embeddings[0] if isinstance(text, str) else embeddings,
            token_usage=token_usage,
        )

    @override
    def close(self) -> None:
        """Do nothing."""
        if self.__client is not None:
            try:
                self.__client.close()
            finally:
                self.__client = None


class OpenAIEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """The settings for OpenAI embedding model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseEmbeddingModelSetup)
    model : str
        Name of the embedding model to use. (Inherited from BaseEmbeddingModelSetup)
    encoding_format : str
        The format to return the embeddings in (default: "float").
        Can be either "float" or "base64".
    dimensions : Optional[int]
        The number of dimensions the resulting output embeddings should have.
        Only supported in text-embedding-3 and later models.
    user : Optional[str]
        A unique identifier representing your end-user, which can help OpenAI
        to monitor and detect abuse.
    additional_kwargs : Dict[str, Any]
        Extra request body properties sent with every embeddings request. Keys
        may not repeat the typed arguments above (model, encoding_format,
        dimensions, user).
    """

    encoding_format: str = Field(
        default="float",
        description='The format to return the embeddings in: "float" or "base64".',
    )
    dimensions: int | None = Field(
        default=None,
        description="The number of dimensions the resulting output embeddings should have.",
    )
    user: str | None = Field(
        default=None,
        description="A unique identifier representing your end-user.",
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Extra request body properties for the OpenAI embeddings API.",
    )

    # "before" mode so non-string keys are stringified as the Java setup does, instead
    # of failing pydantic's Dict[str, Any] check first.
    @field_validator("additional_kwargs", mode="before")
    @classmethod
    def _validate_additional_kwargs(cls, value: Any) -> Dict[str, Any]:
        try:
            return _check_additional_kwargs(value)
        except TypeError as e:  # pydantic only converts ValueError
            raise ValueError(str(e)) from e

    # "before" mode so a null from YAML (``encoding_format: ~``) means the default,
    # as in Java, instead of failing the ``str`` field check. A TypeError would
    # escape pydantic, so it is reported as a ValueError like the other fields.
    @field_validator("encoding_format", mode="before")
    @classmethod
    def _validate_encoding_format(cls, value: Any) -> str:
        try:
            return _check_encoding_format(value)
        except TypeError as e:
            raise ValueError(str(e)) from e

    @field_validator("connection")
    @classmethod
    def _validate_connection(cls, value: Any) -> Any:
        # The field also accepts a connection object; only a name can be blank.
        if isinstance(value, str) and not value.strip():
            msg = "OpenAI embedding requires a non-empty 'connection' on the embedding model setup."
            raise ValueError(msg)
        return value

    # "before" mode so pydantic's lax coercion (True -> 1, "256" -> 256) does not
    # run first; the per-call path and the Java setup reject those inputs too.
    @field_validator("dimensions", mode="before")
    @classmethod
    def _validate_dimensions(cls, value: Any) -> int | None:
        return _check_dimensions(value)

    @field_validator("model")
    @classmethod
    def _validate_model(cls, value: str) -> str:
        return _check_model(value)

    def __init__(
        self,
        *,
        connection: str,
        model: str,
        encoding_format: str = "float",
        dimensions: int | None = None,
        user: str | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            encoding_format=encoding_format,
            dimensions=dimensions,
            user=user,
            additional_kwargs=additional_kwargs,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return OpenAI embedding model configuration."""
        base_kwargs = {
            "model": self.model,
            "encoding_format": self.encoding_format,
        }

        if self.dimensions is not None:
            base_kwargs["dimensions"] = self.dimensions

        if _blank_to_none(self.user, "user") is not None:
            base_kwargs["user"] = self.user

        if self.additional_kwargs:
            base_kwargs["additional_kwargs"] = dict(self.additional_kwargs)

        return base_kwargs
