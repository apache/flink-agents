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
from pyflink.common.typeinfo import RowTypeInfo, Types

from flink_agents.api.agents.types import render_row_type_json_schema

# The same row's schema is asserted in the Java RowTypeJsonSchemaTest.
_MIXED_ROW_SCHEMA = (
    '{"type":"object","properties":{"name":{"type":"string"},'
    '"count":{"type":"integer"},"score":{"type":"number"},'
    '"ok":{"type":"boolean"}},"required":["name","count","score",'
    '"ok"],"additionalProperties":false}'
)

# The same row's schema is asserted in the Java RowTypeJsonSchemaTest.
_UNNAMED_ROW_SCHEMA = (
    '{"type":"object","properties":{"f0":{"type":"string"},'
    '"f1":{"type":"integer"}},"required":["f0","f1"],'
    '"additionalProperties":false}'
)

# The same row's schema is asserted in the Java RowTypeJsonSchemaTest.
_ESCAPED_ROW_SCHEMA = (
    '{"type":"object","properties":{"a\\"b":{"type":"string"},'
    '"c\\\\d":{"type":"string"},"e\\u000bf":{"type":"string"},'
    '"g\\u001fh":{"type":"string"},"i\\nj":{"type":"string"},'
    '"k/l":{"type":"string"},"\uc810\uc218":{"type":"string"},'
    '"m\u2028n":{"type":"string"},"\U0001f600":{"type":"string"}},'
    '"required":["a\\"b","c\\\\d","e\\u000bf","g\\u001fh",'
    '"i\\nj","k/l","\uc810\uc218","m\u2028n","\U0001f600"],'
    '"additionalProperties":false}'
)


def test_renders_flat_row_in_field_order() -> None:
    """A flat row renders as an object schema with every field required."""
    row = RowTypeInfo(
        [Types.STRING(), Types.INT(), Types.DOUBLE(), Types.BOOLEAN()],
        ["name", "count", "score", "ok"],
    )

    assert render_row_type_json_schema(row) == _MIXED_ROW_SCHEMA


def test_maps_numeric_types() -> None:
    """Integral and decimal basic types map to integer and number."""
    row = RowTypeInfo(
        [
            Types.BYTE(),
            Types.SHORT(),
            Types.LONG(),
            Types.BIG_INT(),
            Types.FLOAT(),
            Types.BIG_DEC(),
        ],
        ["b", "s", "l", "bi", "f", "bd"],
    )

    assert render_row_type_json_schema(row) == (
        '{"type":"object","properties":{"b":{"type":"integer"},'
        '"s":{"type":"integer"},"l":{"type":"integer"},'
        '"bi":{"type":"integer"},"f":{"type":"number"},'
        '"bd":{"type":"number"}},"required":["b","s","l",'
        '"bi","f","bd"],"additionalProperties":false}'
    )


@pytest.mark.parametrize(
    "field_type", [Types.CHAR(), Types.INSTANT(), Types.SQL_DATE()]
)
def test_returns_none_for_fields_without_json_type(field_type: object) -> None:
    """A field with no supported mapping leaves the row unrendered."""
    row = RowTypeInfo([Types.STRING(), field_type], ["name", "other"])

    assert render_row_type_json_schema(row) is None


def test_escapes_field_names_like_java() -> None:
    """Escaped field names render the same text as in Java."""
    names = [
        'a"b',
        "c\\d",
        "e\x0bf",
        "g\x1fh",
        "i\nj",
        "k/l",
        "\uc810\uc218",
        "m\u2028n",
        "\U0001f600",
    ]
    row = RowTypeInfo([Types.STRING()] * len(names), names)

    assert render_row_type_json_schema(row) == _ESCAPED_ROW_SCHEMA


def _without_jvm(row: RowTypeInfo, monkeypatch: pytest.MonkeyPatch) -> RowTypeInfo:
    def fail() -> None:
        msg = "the renderer must not start the JVM"
        raise AssertionError(msg)

    monkeypatch.setattr(row, "get_java_type_info", fail)
    return row


def test_unnamed_row_uses_default_names_without_the_jvm(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A row built without names renders f0, f1, ... as Java does."""
    row = _without_jvm(RowTypeInfo([Types.STRING(), Types.INT()]), monkeypatch)

    assert render_row_type_json_schema(row) == _UNNAMED_ROW_SCHEMA


def test_unnamed_unmapped_row_returns_none_without_the_jvm(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An unnamed row with an unmapped field is left unrendered."""
    row = _without_jvm(RowTypeInfo([Types.CHAR()]), monkeypatch)

    assert render_row_type_json_schema(row) is None


@pytest.mark.parametrize(
    ("names", "types"),
    [
        ([], [Types.STRING()]),
        (["a"], [Types.STRING(), Types.INT()]),
        (["x", "x"], [Types.STRING(), Types.INT()]),
    ],
    ids=["empty-names", "fewer-names", "duplicate-names"],
)
def test_returns_none_for_names_java_rejects(names: list[str], types: list) -> None:
    """Names Java would reject leave the row unrendered."""
    assert render_row_type_json_schema(RowTypeInfo(types, names)) is None
