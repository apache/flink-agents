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
"""Output-type inference and conversion helpers for typed agent terminals.

An agent output terminal (``to_datastream`` / ``to_table``) accepts at most one
type declaration. This module normalizes the accepted declarations -- a Pydantic
model, dataclass, named tuple, ``TypedDict``, an explicit ``RowTypeInfo``, or a
Table ``Schema`` -- into the Flink type metadata each terminal needs, converts an
emitted value to a physical ``Row`` for the Table/Row serializer, and
reconstructs declared instances for the typed ``to_datastream`` operator.

``Schema`` and ``RowTypeInfo`` are converted into each other through Flink's own
``TypeConversions`` over the py4j gateway, so the derivation covers the full type
system (nested ``ROW``, arrays, temporal types) instead of a fixed scalar subset.
Only inference from a bare Python annotation (``infer_row_type_info``) stays
limited to common scalars, because a Python type carries no Flink type on its
own.
"""

from __future__ import annotations

import dataclasses
from typing import Any, get_type_hints, is_typeddict

from pydantic import BaseModel
from pyflink.common import Row
from pyflink.common.typeinfo import (
    ExternalTypeInfo,
    RowTypeInfo,
    TypeInformation,
    Types,
    _from_java_type,
)
from pyflink.java_gateway import get_gateway
from pyflink.table import Schema
from pyflink.table.types import _from_java_data_type

__all__ = [
    "infer_row_type_info",
    "is_structured_declaration",
    "reconstruct_instance",
    "resolve_row_type_info",
    "row_shape",
    "row_type_info_to_schema",
    "schema_to_row_type_info",
    "to_row",
]

# python scalar type -> DataStream/Table TypeInformation. A bare Python
# annotation carries no Flink type, so inference from a structured declaration
# is limited to these common scalars; the py4j-backed Schema <-> RowTypeInfo
# derivation below covers the full type system.
_PY_TO_TYPEINFO: dict[type, TypeInformation] = {
    str: Types.STRING(),
    int: Types.LONG(),
    float: Types.DOUBLE(),
    bool: Types.BOOLEAN(),
}


def _is_named_tuple(cls: Any) -> bool:
    """Whether ``cls`` is a ``NamedTuple`` class (a tuple subclass with fields)."""
    return isinstance(cls, type) and issubclass(cls, tuple) and hasattr(cls, "_fields")


def _field_types(cls: Any) -> list[tuple[str, Any]]:
    """Return ordered ``(field_name, python_type)`` pairs for a structured type.

    Supports Pydantic models, dataclasses, named tuples and ``TypedDict``.
    """
    if isinstance(cls, type) and issubclass(cls, BaseModel):
        return [(name, field.annotation) for name, field in cls.model_fields.items()]
    if isinstance(cls, type) and dataclasses.is_dataclass(cls):
        hints = get_type_hints(cls)
        return [(f.name, hints[f.name]) for f in dataclasses.fields(cls)]
    if _is_named_tuple(cls):
        hints = get_type_hints(cls)
        return [(name, hints[name]) for name in cls._fields]
    if is_typeddict(cls):
        return list(get_type_hints(cls).items())
    msg = (
        f"cannot infer output fields from {cls!r}; expected a Pydantic model, "
        "dataclass, named tuple, or TypedDict"
    )
    raise TypeError(msg)


def _typeinfo_of(py_type: Any) -> TypeInformation:
    if py_type not in _PY_TO_TYPEINFO:
        supported = ", ".join(sorted(t.__name__ for t in _PY_TO_TYPEINFO))
        msg = f"unsupported output field type {py_type!r}; supported types: {supported}"
        raise TypeError(msg)
    return _PY_TO_TYPEINFO[py_type]


def is_structured_declaration(decl: Any) -> bool:
    """Whether ``decl`` is a python structured type whose fields can be inferred."""
    if isinstance(decl, TypeInformation | Schema):
        return False
    if not isinstance(decl, type):
        return False
    try:
        _field_types(decl)
    except TypeError:
        return False
    return True


def infer_row_type_info(cls: Any) -> RowTypeInfo:
    """Infer a ``RowTypeInfo`` from a Pydantic/dataclass/named tuple/TypedDict."""
    fields = _field_types(cls)
    return RowTypeInfo(
        [_typeinfo_of(py_type) for _, py_type in fields],
        [name for name, _ in fields],
    )


def _unwrap_type_info(decl: Any) -> Any:
    """Unwrap an ``ExternalTypeInfo`` to its inner ``TypeInformation``.

    A Table terminal accepts an ``ExternalTypeInfo``-wrapped ``RowTypeInfo`` (the
    form its Row serializer needs); resolution and canonicalization compare the
    inner row type, so the wrapper is transparent to declaration equality.
    """
    if isinstance(decl, ExternalTypeInfo):
        return decl._type_info
    return decl


def resolve_row_type_info(decl: Any) -> RowTypeInfo:
    """Normalize a Table output declaration to a ``RowTypeInfo``.

    Accepts an existing ``RowTypeInfo`` (optionally ``ExternalTypeInfo``-wrapped,
    passed through) or a structured python type (inferred). A non-row
    ``TypeInformation`` is rejected because a Table terminal needs a row-shaped
    output.
    """
    decl = _unwrap_type_info(decl)
    if isinstance(decl, RowTypeInfo):
        return decl
    if isinstance(decl, TypeInformation):
        msg = (
            "to_table requires a row-shaped output type, but got a non-row "
            f"TypeInformation {decl!r}; declare a Pydantic model / dataclass / "
            "named tuple / TypedDict, or a RowTypeInfo"
        )
        raise TypeError(msg)
    if is_structured_declaration(decl):
        return infer_row_type_info(decl)
    msg = f"cannot resolve {decl!r} to a row type for Table output"
    raise TypeError(msg)


def row_type_info_to_schema(typeinfo: RowTypeInfo) -> Schema:
    """Derive a physical Table ``Schema`` from a ``RowTypeInfo``.

    Delegates to Flink's ``TypeConversions.fromLegacyInfoToDataType`` over the
    py4j gateway, so nested ``ROW`` and non-scalar fields round-trip instead of
    being rejected.
    """
    gateway = get_gateway()
    type_conversions = gateway.jvm.org.apache.flink.table.types.utils.TypeConversions
    j_data_type = type_conversions.fromLegacyInfoToDataType(
        typeinfo.get_java_type_info()
    )
    row_data_type = _from_java_data_type(j_data_type)
    return Schema.new_builder().from_row_data_type(row_data_type).build()


def _resolve_data_type(abstract_type: Any) -> Any:
    """Resolve a schema column's ``AbstractDataType`` to a concrete ``DataType``.

    A column declared with a ``DataTypes`` object is already a resolved
    ``DataType`` and is returned as-is. A column declared with a SQL type string
    (for example ``column("id", "BIGINT")``) is an ``UnresolvedDataType`` that
    ``TypeConversions.fromDataTypeToLegacyInfo`` rejects, so its SQL text is
    parsed through Flink's ``LogicalTypeParser`` first -- the same resolution
    PyFlink applies when the schema is handed to ``from_data_stream``.
    """
    gateway = get_gateway()
    data_type_class = abstract_type.getClass().forName(
        "org.apache.flink.table.types.DataType"
    )
    if data_type_class.isInstance(abstract_type):
        return abstract_type
    sql_type = abstract_type.toString()
    if sql_type.startswith("[") and sql_type.endswith("]"):
        sql_type = sql_type[1:-1]
    type_conversions = gateway.jvm.org.apache.flink.table.types.utils.TypeConversions
    logical_type_parser = (
        gateway.jvm.org.apache.flink.table.types.logical.utils.LogicalTypeParser
    )
    class_loader = gateway.jvm.java.lang.Thread.currentThread().getContextClassLoader()
    logical_type = logical_type_parser.parse(sql_type, class_loader)
    return type_conversions.fromLogicalToDataType(logical_type)


def schema_to_row_type_info(schema: Schema) -> RowTypeInfo:
    """Derive a ``RowTypeInfo`` from the physical columns of a Table ``Schema``.

    Each column type is resolved to a concrete ``DataType`` -- a SQL type string
    such as ``"BIGINT"`` is parsed first -- then converted to a legacy
    ``TypeInformation`` through Flink's ``fromDataTypeToLegacyInfo``. The
    assembled Java ``RowTypeInfo`` is mapped back by PyFlink's
    ``_from_java_type``, so nested ``ROW``, array, and temporal columns are
    preserved.
    """
    gateway = get_gateway()
    type_conversions = gateway.jvm.org.apache.flink.table.types.utils.TypeConversions
    j_row_type_info = gateway.jvm.org.apache.flink.api.java.typeutils.RowTypeInfo
    j_type_info = gateway.jvm.org.apache.flink.api.common.typeinfo.TypeInformation
    j_string = gateway.jvm.java.lang.String

    # Only physical columns feed the DataStream Row; computed and metadata
    # columns are derived by the Table planner rather than read from the agent
    # output, mirroring the Java toTable(Schema) conversion.
    columns = [
        column
        for column in schema._j_schema.getColumns()
        if column.getClass().getSimpleName() == "UnresolvedPhysicalColumn"
    ]
    j_types = gateway.new_array(j_type_info, len(columns))
    j_names = gateway.new_array(j_string, len(columns))
    for i, column in enumerate(columns):
        data_type = _resolve_data_type(column.getDataType())
        j_types[i] = type_conversions.fromDataTypeToLegacyInfo(data_type)
        j_names[i] = column.getName()
    return _from_java_type(j_row_type_info(j_types, j_names))


def row_shape(row_type_info: RowTypeInfo) -> tuple:
    """Picklable nested field-name shape of a ``RowTypeInfo``.

    The result is ``(names, children)`` where ``children[i]`` is the shape of a
    nested ``RowTypeInfo`` field and ``None`` otherwise. It carries only the row
    nesting that value conversion needs, so it can be captured in a worker
    closure; a ``RowTypeInfo`` itself holds py4j objects once its Java type has
    been materialized and cannot be pickled into the closure.
    """
    names = list(row_type_info.get_field_names())
    children = [
        row_shape(field_type) if isinstance(field_type, RowTypeInfo) else None
        for field_type in row_type_info.get_field_types()
    ]
    return (names, children)


def to_row(value: Any, shape: tuple) -> Any:
    """Adapt an emitted value to a physical ``Row`` using a :func:`row_shape`.

    The raw agent output crosses a JSON boundary, so a structured value arrives
    as a dict (a Pydantic model / dataclass degraded to its fields) or, for a
    ``NamedTuple``, as a positional array; a value the agent emitted as a ``Row``
    is reconstructed as one. Only fields the shape marks as nested rows are
    converted recursively; every other value -- scalar, map, or list -- is left
    untouched for its own coder, so no column kind is silently reshaped. This is
    the payload of the Table/Row conversion operator.
    """
    if isinstance(value, Row):
        return value
    names, children = shape
    if isinstance(value, dict):
        fields = [
            to_row(value.get(name), child) if child is not None else value.get(name)
            for name, child in zip(names, children, strict=False)
        ]
        return Row(*fields)
    if isinstance(value, list | tuple) and len(value) == len(names):
        # A NamedTuple degrades to a positional array across the OutputEvent JSON
        # boundary (mirroring reconstruct_instance), so rebuild the Row by field
        # order. A length mismatch means the sequence is one column's own value
        # (for example an ARRAY), which is left untouched for its coder.
        fields = [
            to_row(item, child) if child is not None else item
            for item, child in zip(value, children, strict=False)
        ]
        return Row(*fields)
    return value


def reconstruct_instance(cls: Any, data: Any) -> Any:
    """Rebuild an instance of a structured declaration from its emitted form.

    This is the payload of the typed ``to_datastream`` conversion operator. A
    Pydantic model / dataclass degrades to a dict across the JSON output boundary
    (only ``Row`` is reconstructed there) while a ``NamedTuple`` degrades to a
    positional array, so the declared type is restored here; a ``Row`` is adapted
    through its field mapping, a ``TypedDict`` declaration is already satisfied
    by a dict, and a value that is already an instance is passed through.
    """
    if isinstance(data, Row):
        data = data.as_dict()
    if is_typeddict(cls):
        if not isinstance(data, dict):
            msg = f"cannot reconstruct TypedDict {cls!r} from {type(data).__name__}"
            raise TypeError(msg)
        return data
    if isinstance(cls, type) and isinstance(data, cls):
        return data
    if _is_named_tuple(cls) and isinstance(data, list | tuple):
        # A NamedTuple crosses the OutputEvent JSON boundary as a positional
        # array, so rebuild it by field order instead of by name.
        return cls(*data)
    if not isinstance(data, dict):
        msg = (
            f"cannot reconstruct {cls!r} from {type(data).__name__}; expected a "
            "mapping of field names to values"
        )
        raise TypeError(msg)
    if isinstance(cls, type) and issubclass(cls, BaseModel):
        return cls.model_validate(data)
    try:
        return cls(**data)
    except TypeError as error:
        msg = f"cannot reconstruct {cls!r} from fields {sorted(data)}: {error}"
        raise TypeError(msg) from error
