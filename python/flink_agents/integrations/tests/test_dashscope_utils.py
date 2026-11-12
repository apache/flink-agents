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

from flink_agents.integrations.dashscope_utils import usage_counters


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
