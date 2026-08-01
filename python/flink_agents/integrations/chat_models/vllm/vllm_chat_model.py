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
from typing import Any

from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
    OpenAIChatModelSetup,
)

DEFAULT_VLLM_API_BASE_URL = "http://localhost:8000/v1"
"""Default base URL of a local ``vllm serve`` instance."""

DEFAULT_VLLM_API_KEY = "EMPTY"
"""Placeholder credential used when the vLLM server is started without
``--api-key``. The OpenAI SDK requires a non-empty key, but the server ignores
its value."""


class VLLMChatModelConnection(OpenAIChatModelConnection):
    """Connection to a `vLLM <https://docs.vllm.ai>`_ server.

    vLLM exposes an OpenAI-compatible API, so this connection reuses
    :class:`OpenAIChatModelConnection` with vLLM-friendly defaults:

    * ``api_base_url`` defaults to ``http://localhost:8000/v1``, the default
      address of ``vllm serve``.
    * ``api_key`` defaults to a placeholder, since vLLM servers started without
      ``--api-key`` do not require a credential. Set it explicitly when the
      server is started with ``--api-key``.

    All other attributes (``timeout``, ``max_retries``, ``default_headers``,
    ``reuse_client``) behave exactly as in :class:`OpenAIChatModelConnection`.
    """

    def __init__(
        self,
        *,
        api_key: str | None = None,
        api_base_url: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            api_key=api_key or DEFAULT_VLLM_API_KEY,
            api_base_url=api_base_url or DEFAULT_VLLM_API_BASE_URL,
            **kwargs,
        )


class VLLMChatModelSetup(OpenAIChatModelSetup):
    """Settings for a chat model served by vLLM.

    Behaves like :class:`OpenAIChatModelSetup` with one difference: ``model``
    is required and has no default, because a vLLM server only serves the
    model(s) it was started with — there is no meaningful universal default.
    The value must match the model name announced by the server (see
    ``vllm serve <model>``, or query ``GET /v1/models``).
    """

    def __init__(self, *, model: str | None = None, **kwargs: Any) -> None:
        """Init method."""
        if not model:
            msg = (
                "model is required for vLLM: it must match the model name served "
                "by the vLLM server (see `vllm serve <model>` or GET /v1/models)."
            )
            raise ValueError(msg)
        super().__init__(model=model, **kwargs)
