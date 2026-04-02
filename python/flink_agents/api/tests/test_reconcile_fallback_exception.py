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

from flink_agents.api.runner_context import ReconcileFallbackException


def test_reconcile_fallback_exception_preserves_cause() -> None:
    cause = ValueError("indeterminate")

    exc = ReconcileFallbackException(cause)

    assert exc.cause is cause
    assert str(exc) == "indeterminate"


def test_reconcile_fallback_exception_requires_exception_cause() -> None:
    with pytest.raises(TypeError, match="requires a BaseException cause"):
        ReconcileFallbackException("not-an-exception")  # type: ignore[arg-type]
