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
import pytest
from openai.types.chat import ChatCompletionMessage

from flink_agents.integrations.chat_models.openai.openai_utils import (
    convert_from_openai_message,
)


@pytest.mark.parametrize("refusal", ["I cannot help with that", ""])
def test_refusal_is_preserved_in_extra_args(refusal: str) -> None:
    """A provider refusal reaches the caller through extra_args."""
    # A refused response carries no content, so the reason is the only thing that
    # distinguishes it from a genuinely empty completion. An empty reason is still
    # a refusal, which a truthiness guard would silently drop. Callers hand in an
    # extra_args already holding token metrics, so recording the reason must add to
    # that dict rather than replace it. The reason belongs in extra_args alone:
    # folding it into content would make a refusal read as an ordinary answer.
    message = ChatCompletionMessage(role="assistant", content=None, refusal=refusal)

    result = convert_from_openai_message(message, {"promptTokens": 3})

    assert result.extra_args["refusal"] == refusal
    assert result.extra_args["promptTokens"] == 3
    assert result.content == ""


def test_no_refusal_key_when_refusal_absent() -> None:
    """A response that was not refused leaves no refusal key behind."""
    # extra_args is merged back into the outbound assistant message, so a null
    # refusal key here would be echoed to the provider on every later request.
    message = ChatCompletionMessage(role="assistant", content="ok", refusal=None)

    result = convert_from_openai_message(message, {})

    assert "refusal" not in result.extra_args
