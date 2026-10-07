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
from pydantic import BaseModel

from flink_agents.plan.actions.tool_result_utils import (
    normalize_agent_result,
    to_chat_message_content,
)


class Verdict(BaseModel):
    """The result a sub-agent that declares a result type produces."""

    approved: bool
    note: str = ""
    score: float = 0.0


def test_normalize_reduces_nested_containers_to_plain_ones() -> None:
    raw = {"outer": {"inner": [1, "two"]}, "items": (3, 4)}

    assert normalize_agent_result(raw) == {
        "outer": {"inner": [1, "two"]},
        "items": [3, 4],
    }


def test_normalize_keeps_scalars_and_none() -> None:
    assert normalize_agent_result("text") == "text"
    assert normalize_agent_result(7) == 7
    assert normalize_agent_result(True) is True
    assert normalize_agent_result(None) is None


def test_normalize_reports_where_an_unsupported_value_was_found() -> None:
    with pytest.raises(TypeError) as error:
        normalize_agent_result({"outer": [object()]})

    assert "result.outer[0]" in str(error.value)
    assert "found object" in str(error.value)


def test_normalize_rejects_a_non_string_mapping_key() -> None:
    with pytest.raises(TypeError) as error:
        normalize_agent_result({1: "a"})

    assert str(error.value) == "Map keys in sub-agent result must be strings at result"


def test_normalize_rejects_a_non_finite_number() -> None:
    with pytest.raises(ValueError) as error:
        normalize_agent_result({"ratio": float("inf")})

    assert str(error.value) == "Non-finite number in sub-agent result at result.ratio"

    with pytest.raises(ValueError) as error:
        normalize_agent_result(float("nan"))

    assert str(error.value) == "Non-finite number in sub-agent result at result"


def test_normalize_walks_a_top_level_sequence_by_index() -> None:
    assert normalize_agent_result([1, 2]) == [1, 2]

    with pytest.raises(TypeError) as error:
        normalize_agent_result([object()])

    assert "result[0]" in str(error.value)


def test_normalize_reads_a_declared_result_type_into_generic_form() -> None:
    """Declaring a result type is what makes a result JSON cannot express
    reportable: the type says how to read it.
    """
    assert normalize_agent_result(Verdict(approved=True, note="clean"), Verdict) == {
        "approved": True,
        "note": "clean",
        "score": 0.0,
    }


def test_normalize_narrows_a_wider_result_to_what_the_type_declares() -> None:
    raw = {"approved": True, "note": "clean", "score": 1.5, "extra": 1}

    assert normalize_agent_result(raw, Verdict) == {
        "approved": True,
        "note": "clean",
        "score": 1.5,
    }


def test_an_undeclared_result_type_still_rejects_a_model_instance() -> None:
    """The undeclared path is unchanged: a model instance is still what it refuses."""
    with pytest.raises(TypeError, match="must be JSON-compatible at result"):
        normalize_agent_result(Verdict(approved=True))


def test_declaring_object_is_the_same_as_declaring_nothing() -> None:
    raw = {"items": [1, 2]}

    assert normalize_agent_result(raw, object) == normalize_agent_result(raw)


def test_a_declared_result_type_still_rejects_what_json_cannot_express() -> None:
    """A declared type can still render a field JSON cannot express."""
    raw = {"approved": True, "note": "clean", "score": float("inf")}

    with pytest.raises(ValueError) as error:
        normalize_agent_result(raw, Verdict)

    assert str(error.value) == "Non-finite number in sub-agent result at result.score"


def test_chat_message_content_renders_a_missing_result_as_json_null() -> None:
    assert to_chat_message_content(None) == "null"


def test_chat_message_content_renders_scalars_and_containers() -> None:
    assert to_chat_message_content("text") == "text"
    assert to_chat_message_content(7) == "7"
    # A boolean has to read as JSON to the model rather than as Python's True.
    assert to_chat_message_content(True) == "true"
    assert to_chat_message_content({"a": 1, "b": [2]}) == '{"a":1,"b":[2]}'
    assert to_chat_message_content([1, 2]) == "[1,2]"
