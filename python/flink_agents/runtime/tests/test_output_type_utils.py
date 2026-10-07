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
"""Unit tests for output type inference and conversion helpers."""

import pytest
from pydantic import BaseModel
from pyflink.common import Row
from pyflink.common.typeinfo import ExternalTypeInfo, RowTypeInfo, Types
from pyflink.table import DataTypes, Schema

from flink_agents.api.events.event import Event, OutputEvent
from flink_agents.runtime.output_type_utils import (
    infer_row_type_info,
    is_structured_declaration,
    reconstruct_instance,
    resolve_row_type_info,
    row_shape,
    row_type_info_to_schema,
    schema_to_row_type_info,
    to_row,
)
from flink_agents.runtime.tests.output_type_fixtures import (
    DcOutput,
    ModelOutput,
    NtOutput,
    TdOutput,
)


def _row_spec(typeinfo: RowTypeInfo) -> tuple:
    """Canonical comparable form of a flat row type: names plus type strings."""
    return (
        list(typeinfo.get_field_names()),
        [str(t) for t in typeinfo.get_field_types()],
    )


def test_infer_agrees_across_structured_types():
    expected = _row_spec(RowTypeInfo([Types.LONG(), Types.STRING()], ["id", "label"]))
    for cls in (DcOutput, NtOutput, TdOutput):
        assert _row_spec(infer_row_type_info(cls)) == expected


def test_infer_pydantic_fields():
    typeinfo = infer_row_type_info(ModelOutput)
    assert typeinfo.get_field_names() == ["id", "label", "score"]
    assert [str(t) for t in typeinfo.get_field_types()] == ["Long", "String", "Double"]


def test_is_structured_declaration():
    assert is_structured_declaration(ModelOutput)
    assert is_structured_declaration(DcOutput)
    assert not is_structured_declaration(Types.LONG())
    assert not is_structured_declaration(42)


def test_row_type_info_to_schema_round_trip():
    typeinfo = infer_row_type_info(ModelOutput)
    schema = row_type_info_to_schema(typeinfo)
    assert _row_spec(schema_to_row_type_info(schema)) == _row_spec(typeinfo)


def test_schema_to_row_type_info():
    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("label", DataTypes.STRING())
        .build()
    )
    typeinfo = schema_to_row_type_info(schema)
    assert typeinfo.get_field_names() == ["id", "label"]
    assert [str(t) for t in typeinfo.get_field_types()] == ["Long", "String"]


def test_schema_to_row_type_info_sql_type_strings():
    """A column declared with a SQL type string resolves like a ``DataTypes`` one.

    ``column("id", "BIGINT")`` stores an ``UnresolvedDataType``; resolving it
    before the legacy conversion yields the same row type as the equivalent
    ``DataTypes`` declaration, for scalars and a nested ``ROW`` alike.
    """
    schema = (
        Schema.new_builder()
        .column("id", "BIGINT")
        .column("label", "STRING")
        .column("nested", "ROW<x INT, y STRING>")
        .build()
    )
    typeinfo = schema_to_row_type_info(schema)
    assert typeinfo.get_field_names() == ["id", "label", "nested"]
    assert [str(t) for t in typeinfo.get_field_types()[:2]] == ["Long", "String"]
    nested = typeinfo.get_field_types()[2]
    assert isinstance(nested, RowTypeInfo)
    assert nested.get_field_names() == ["x", "y"]

    # The SQL strings agree with the equivalent ``DataTypes`` object columns.
    object_schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("label", DataTypes.STRING())
        .build()
    )
    sql_schema = (
        Schema.new_builder().column("id", "BIGINT").column("label", "STRING").build()
    )
    assert _row_spec(schema_to_row_type_info(sql_schema)) == _row_spec(
        schema_to_row_type_info(object_schema)
    )


def test_schema_to_row_type_info_skips_computed_and_metadata_columns():
    """Only physical columns feed the derived row type.

    Computed and metadata columns are planner-derived rather than read from the
    agent output, so ``schema_to_row_type_info`` skips them; this mirrors the
    Java ``toTable(Schema)`` conversion.
    """
    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("label", DataTypes.STRING())
        .column_by_expression("id_plus_one", "id + 1")
        .column_by_metadata("rowtime", "TIMESTAMP_LTZ(3)")
        .build()
    )
    typeinfo = schema_to_row_type_info(schema)
    assert typeinfo.get_field_names() == ["id", "label"]
    assert [str(t) for t in typeinfo.get_field_types()] == ["Long", "String"]


def test_nested_row_schema_derives_and_converts():
    """A nested ROW column survives py4j derivation in both directions.

    This is the full-type coverage the py4j-backed conversion adds over the
    previous scalar-subset mapping, which rejected a nested ROW column outright.
    """
    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column(
            "nested",
            DataTypes.ROW(
                [
                    DataTypes.FIELD("x", DataTypes.INT()),
                    DataTypes.FIELD("y", DataTypes.STRING()),
                ]
            ),
        )
        .build()
    )
    typeinfo = schema_to_row_type_info(schema)
    assert typeinfo.get_field_names() == ["id", "nested"]
    nested = typeinfo.get_field_types()[1]
    assert isinstance(nested, RowTypeInfo)
    assert nested.get_field_names() == ["x", "y"]

    # A nested dict is converted to a nested Row along the picklable shape.
    shape = row_shape(typeinfo)
    row = to_row({"id": 1, "nested": {"x": 2, "y": "z"}}, shape)
    assert row == Row(1, Row(2, "z"))

    # The nested row type derives an equivalent schema and maps back.
    round_tripped = schema_to_row_type_info(row_type_info_to_schema(typeinfo))
    assert round_tripped.get_field_names() == ["id", "nested"]
    rt_nested = round_tripped.get_field_types()[1]
    assert isinstance(rt_nested, RowTypeInfo)
    assert rt_nested.get_field_names() == ["x", "y"]


def test_resolve_passes_through_and_infers():
    typeinfo = infer_row_type_info(ModelOutput)
    assert _row_spec(resolve_row_type_info(typeinfo)) == _row_spec(typeinfo)
    assert _row_spec(resolve_row_type_info(DcOutput)) == _row_spec(
        infer_row_type_info(DcOutput)
    )


def test_resolve_rejects_scalar_typeinfo():
    with pytest.raises(TypeError, match="row-shaped"):
        resolve_row_type_info(Types.LONG())


def test_resolve_unwraps_external_type_info():
    typeinfo = infer_row_type_info(ModelOutput)
    wrapped = ExternalTypeInfo(typeinfo)
    assert _row_spec(resolve_row_type_info(wrapped)) == _row_spec(typeinfo)


def test_row_shape_marks_only_nested_rows():
    names, children = row_shape(infer_row_type_info(ModelOutput))
    assert names == ["id", "label", "score"]
    assert children == [None, None, None]


def test_to_row_from_dict():
    shape = row_shape(infer_row_type_info(ModelOutput))
    assert list(to_row({"id": 2, "label": "x", "score": 2.5}, shape)) == [2, "x", 2.5]


def test_to_row_passes_through_row():
    shape = row_shape(infer_row_type_info(ModelOutput))
    existing = Row(3, "y", 3.5)
    assert to_row(existing, shape) is existing


def test_to_row_passes_through_non_dict():
    # A non-mapping value is left untouched for its own coder.
    shape = row_shape(infer_row_type_info(ModelOutput))
    assert to_row(42, shape) == 42


def test_infer_rejects_unsupported_field_type():
    class Bad(BaseModel):
        payload: bytes

    with pytest.raises(TypeError, match="unsupported output field type"):
        infer_row_type_info(Bad)


def test_reconstruct_instance_from_dict():
    data = {"id": 1, "label": "a"}
    assert reconstruct_instance(DcOutput, data) == DcOutput(id=1, label="a")
    assert reconstruct_instance(NtOutput, data) == NtOutput(id=1, label="a")
    assert reconstruct_instance(TdOutput, data) == data
    instance = reconstruct_instance(ModelOutput, {"id": 1, "label": "a", "score": 1.5})
    assert isinstance(instance, ModelOutput)


def test_reconstruct_named_tuple_through_output_event_boundary():
    """A ``NamedTuple`` degrades to a positional array across the JSON boundary.

    The agent emits ``NtOutput``; ``OutputEvent.model_dump_json`` serializes the
    tuple as a JSON array, so the value ``reconstruct_instance`` receives is
    ``[1, "good"]`` rather than a mapping and must be rebuilt by field order.
    """
    event = OutputEvent(output=NtOutput(1, "good"))
    degraded = Event.from_json(event.model_dump_json()).attributes["output"]
    assert degraded == [1, "good"]
    assert reconstruct_instance(NtOutput, degraded) == NtOutput(1, "good")


def test_reconstruct_instance_from_row():
    assert reconstruct_instance(DcOutput, Row(id=1, label="a")) == DcOutput(
        id=1, label="a"
    )


def test_reconstruct_instance_passes_through_instance():
    existing = ModelOutput(id=1, label="a", score=1.5)
    assert reconstruct_instance(ModelOutput, existing) is existing


def test_reconstruct_instance_rejects_non_mapping():
    with pytest.raises(TypeError, match="expected a mapping"):
        reconstruct_instance(DcOutput, 42)
