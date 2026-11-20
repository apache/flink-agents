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
from types import SimpleNamespace

import pytest

from flink_agents.integrations.dashscope_utils import (
    usage_counters,
    validate_request_timeout,
)


def test_usage_counters_keep_scalar_fields_and_drop_bools() -> None:
    usage = SimpleNamespace(
        model_dump=lambda exclude_defaults: {
            "total_tokens": 3,
            "enabled": True,
            "details": {"cached": 1},
        }
    )

    assert usage_counters(usage) == {"total_tokens": 3}
    assert usage_counters({"duration": 0, "ratio": "16:9"}) == {
        "duration": 0,
        "ratio": "16:9",
    }
    assert usage_counters(None) == {}


def test_validate_request_timeout_accepts_none_and_positive_numbers() -> None:
    assert validate_request_timeout(None) is None
    assert validate_request_timeout(12) == 12
    assert validate_request_timeout(0.5) == 0.5


@pytest.mark.parametrize(
    ("timeout", "error_type"),
    [
        (0, ValueError),
        (-1.5, ValueError),
        (float("nan"), ValueError),
        (float("inf"), ValueError),
        (True, TypeError),
        (False, TypeError),
        ("10", TypeError),
    ],
)
def test_validate_request_timeout_rejects_invalid_values(
    timeout: object, error_type: type[Exception]
) -> None:
    with pytest.raises(error_type, match="positive finite number"):
        validate_request_timeout(timeout)  # type: ignore[arg-type]
