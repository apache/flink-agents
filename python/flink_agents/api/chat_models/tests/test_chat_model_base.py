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
from typing import Any, Dict

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_models.chat_model import BaseChatModelSetup


class _MinimalChatModelSetup(BaseChatModelSetup):
    """Minimal subclass that omits the `model` field declaration.

    Used to assert the `model` field is inherited from `BaseChatModelSetup`.
    """

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings derived from the inherited `model` field."""
        return {"model": self.model}


def test_inherits_model_field_from_base() -> None:
    """A subclass that omits `model` still exposes it via inheritance."""
    setup = _MinimalChatModelSetup(connection="c", model="m1")
    assert setup.model == "m1"


def test_missing_model_raises_validation_error() -> None:
    """Constructing without `model` must raise a Pydantic ValidationError."""
    with pytest.raises(ValidationError):
        _MinimalChatModelSetup(connection="c")
