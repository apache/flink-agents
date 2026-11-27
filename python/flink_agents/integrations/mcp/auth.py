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
"""Authentication configuration for MCP servers.

``MCPServer.auth`` accepts exactly the four classes below, told apart by
``authType``. Each is plain data that serializes to JSON. ``BasicAuth``,
``BearerTokenAuth`` and ``ApiKeyAuth`` use the same JSON shape as the Java
classes of the same names. ``CustomAuth`` is Python only: it names a factory
that builds any ``httpx.Auth``, such as the MCP SDK's ``OAuthClientProvider``.
"""

import importlib
from abc import ABC, abstractmethod
from typing import Annotated, Any, Dict, Generator, Literal

import httpx
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictStr,
    field_validator,
)
from typing_extensions import override


class MCPAuth(BaseModel, ABC):
    """Base class of the authentication configurations of an MCP server.

    The set is closed: ``MCPServer`` accepts only the subclasses in this module.
    """

    model_config = ConfigDict(
        frozen=True,
        extra="forbid",
        validate_by_name=True,
        validate_by_alias=True,
        serialize_by_alias=True,
    )

    @abstractmethod
    def to_httpx_auth(self) -> httpx.Auth:
        """Build the ``httpx.Auth`` that applies this configuration to requests."""


class BasicAuth(MCPAuth):
    """Sends ``Authorization: Basic <base64(username:password)>``.

    Example::

        BasicAuth(username="user", password="secret")
    """

    auth_type: Literal["basic"] = Field(default="basic", alias="authType")
    username: StrictStr
    password: StrictStr = Field(repr=False)

    @override
    def to_httpx_auth(self) -> httpx.Auth:
        return httpx.BasicAuth(self.username, self.password)


class BearerTokenAuth(MCPAuth):
    """Sends ``Authorization: Bearer <token>``.

    Example::

        BearerTokenAuth(token="your-oauth-token")
    """

    auth_type: Literal["bearer"] = Field(default="bearer", alias="authType")
    token: StrictStr = Field(repr=False)

    @override
    def to_httpx_auth(self) -> httpx.Auth:
        return _HeaderAuth("Authorization", f"Bearer {self.token}")


class ApiKeyAuth(MCPAuth):
    """Sends the API key in the header named ``header_name``.

    Example::

        ApiKeyAuth(header_name="X-API-Key", api_key="your-api-key")
    """

    auth_type: Literal["api_key"] = Field(default="api_key", alias="authType")
    header_name: StrictStr = Field(alias="headerName")
    api_key: StrictStr = Field(alias="apiKey", repr=False)

    @override
    def to_httpx_auth(self) -> httpx.Auth:
        return _HeaderAuth(self.header_name, self.api_key)


class CustomAuth(MCPAuth):
    """Builds an ``httpx.Auth`` by calling a factory with JSON keyword arguments.

    Use it for OAuth or any other ``httpx.Auth``. ``factory`` has the form
    ``<module>:<qualname>``, for example ``my_pkg.auth:make_oauth`` or
    ``my_pkg.auth:MyAuth``. It must be importable where the agent runs.

    The factory is imported and called with ``arguments`` when the server first
    connects, so it must name trusted code, like a resource's class name. Each
    ``MCPServer`` instance calls it once and reuses the result for its later
    connections. Parallel subtasks build their own instances, possibly in
    different processes, so an OAuth provider that should not authorize once per
    subtask needs token storage outside the process.

    Example::

        CustomAuth(
            factory="my_pkg.auth:make_oauth",
            arguments={"server_url": "https://mcp.example.com/mcp"},
        )
    """

    auth_type: Literal["custom"] = Field(default="custom", alias="authType")
    factory: StrictStr
    arguments: Dict[str, JsonValue] = Field(default_factory=dict, repr=False)

    @field_validator("factory")
    @classmethod
    def _validate_factory(cls, value: str) -> str:
        module, sep, qualname = value.partition(":")
        if not sep or not all(
            part.isidentifier() for part in [*module.split("."), *qualname.split(".")]
        ):
            msg = (
                f"CustomAuth factory {value!r} must be of the form "
                "'<module>:<qualname>', e.g. 'my_pkg.auth:make_oauth'."
            )
            raise ValueError(msg)
        return value

    @override
    def to_httpx_auth(self) -> httpx.Auth:
        module_name, _, qualname = self.factory.partition(":")
        target: Any = importlib.import_module(module_name)
        for part in qualname.split("."):
            target = getattr(target, part)
        auth = target(**self.arguments)
        if not isinstance(auth, httpx.Auth):
            msg = (
                f"CustomAuth factory {self.factory!r} returned "
                f"{type(auth).__name__}, expected an httpx.Auth instance."
            )
            raise TypeError(msg)
        return auth


AnyMCPAuth = Annotated[
    BasicAuth | BearerTokenAuth | ApiKeyAuth | CustomAuth,
    Field(discriminator="auth_type"),
]
"""An MCP server's auth: one of the configurations above, chosen by ``authType``."""


class _HeaderAuth(httpx.Auth):
    """Sets one header on every request."""

    def __init__(self, name: str, value: str) -> None:
        self._name = name
        self._value = value

    def auth_flow(
        self, request: httpx.Request
    ) -> Generator[httpx.Request, httpx.Response, None]:
        request.headers[self._name] = self._value
        yield request
