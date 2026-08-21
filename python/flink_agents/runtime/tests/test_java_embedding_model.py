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
from unittest.mock import MagicMock

import pytest

from flink_agents.runtime.java.java_embedding_model import (
    JavaEmbeddingModelConnectionImpl,
    JavaEmbeddingModelSetupImpl,
)


class _JavaTokenUsage:
    def getPromptTokens(self) -> int:
        return 7

    def getTotalTokens(self) -> int:
        return 9


class _JavaEmbeddingResult:
    def getEmbeddings(self) -> list[list[float]]:
        return [[0.1, 0.2], [0.3, 0.4]]

    def getTokenUsage(self) -> _JavaTokenUsage:
        return _JavaTokenUsage()


@pytest.mark.parametrize(
    ("wrapper_class", "kwargs"),
    [
        (JavaEmbeddingModelConnectionImpl, {}),
        (
            JavaEmbeddingModelSetupImpl,
            {"connection": "connection", "model": "test-model"},
        ),
    ],
)
def test_java_embedding_wrappers_preserve_usage(
    wrapper_class: type[JavaEmbeddingModelConnectionImpl | JavaEmbeddingModelSetupImpl],
    kwargs: dict[str, str],
) -> None:
    j_resource = MagicMock()
    j_resource.embedWithUsage.return_value = _JavaEmbeddingResult()
    wrapper = wrapper_class(j_resource, MagicMock(), **kwargs)

    result = wrapper.embed_with_usage(["first", "second"], batch_size=2)

    assert result.embeddings == [[0.1, 0.2], [0.3, 0.4]]
    assert result.token_usage is not None
    assert result.token_usage.prompt_tokens == 7
    assert result.token_usage.total_tokens == 9
    j_resource.embedWithUsage.assert_called_once_with(
        ["first", "second"], {"batch_size": 2}
    )
