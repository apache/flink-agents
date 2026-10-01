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
"""Shared structured output types for the typed-terminal tests.

The output-type helpers and the typed ``to_datastream`` / ``to_table`` terminals
are exercised with the same four kinds of python structured declaration: a
Pydantic model, a dataclass, a named tuple and a ``TypedDict``. They live in one
module so the helper tests and the environment tests share a single definition
instead of each redeclaring equivalent types.
"""

import dataclasses
from typing import NamedTuple, TypedDict

from pydantic import BaseModel


class ModelOutput(BaseModel):
    id: int
    label: str
    score: float


@dataclasses.dataclass
class DcOutput:
    id: int
    label: str


class NtOutput(NamedTuple):
    id: int
    label: str


class TdOutput(TypedDict):
    id: int
    label: str
