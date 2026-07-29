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
from abc import ABC, abstractmethod
from enum import Enum
from typing import TYPE_CHECKING, Any, Dict, List, Union

from pydantic import BaseModel

if TYPE_CHECKING:
    from flink_agents.api.memory_reference import MemoryRef


# Exact builtin types Pemja materializes into native, checkpoint-stable JVM values.
# Exact-type (not isinstance): a str/int Enum or numpy scalar is a subclass that Pemja
# PyObject-wraps despite passing isinstance — accepting it would defeat the validator.
# Exact `bytes` converts to a Java byte[]; bytearray and bytes-subclasses are PyObject-
# wrapped, and the exact-type check excludes both for free.
_CHECKPOINT_STABLE_SCALARS = (bool, int, float, str, bytes)


def validate_memory_value(path: str, value: Any) -> None:
    """Reject memory values that are not recursively checkpoint-stable.

    Python memory values cross the Pemja boundary into Flink state. Only values Pemja
    materializes into native JVM types survive checkpoint and restore; anything else is
    stored as a stale PyObject wrapper and crashes on restore. Raises TypeError with a
    clear, actionable message naming the offending location, type, and a conversion.

    Parameters
    ----------
    path: str
        The memory path the value is being set at, used to build the error breadcrumb.
    value: Any
        The value to validate. Must be recursively composed of None, bool, int, float,
        str, bytes, list, or dict with str keys.
    """
    _validate(value, f"value at memory path {path!r}")


def _validate(value: Any, where: str) -> None:
    if value is None or type(value) in _CHECKPOINT_STABLE_SCALARS:
        return
    if isinstance(value, MemoryObject):
        msg = (
            f"{where} is a MemoryObject; use new_object(...) to store a nested object "
            f"instead of passing it to set()."
        )
        raise TypeError(msg)
    if type(value) is list:
        for i, item in enumerate(value):
            _validate(item, f"{where}[{i}]")
        return
    if type(value) is dict:
        for key, val in value.items():
            if type(key) is not str:
                msg = (
                    f"{where} has a non-str key {key!r} ({type(key).__name__}); memory "
                    f"dict keys must be str. Convert with "
                    f"{{str(k): v for k, v in value.items()}}."
                )
                raise TypeError(msg)
            _validate(val, f"{where}[{key!r}]")
        return
    msg = (
        f"{where} has type {type(value).__name__!r}, which is not checkpoint-stable. "
        f"Python memory values must be recursively composed of None, bool, int, float, "
        f"str, bytes, list, or dict with str keys, because they cross the Pemja boundary "
        f"into Flink state and non-primitive objects cannot be safely "
        f"checkpointed/restored. "
        f"Materialize it first, e.g. str(value) for a UUID, value.model_dump(mode='json')"
        f" for a Pydantic model, or list(value) for a tuple/set."
    )
    raise TypeError(msg)


class MemoryType(Enum):
    """Memory types based on MemoryObject."""

    SENSORY = ("sensory",)
    SHORT_TERM = "short_term"


class MemoryObject(BaseModel, ABC):
    """Representation of an object in the short-term memory.

    A direct field is a field which stores concrete data directly, while an indirect
    filed is just a field which represents a nested object. Fields can be accessed
    using an absolute or relative path.
    """

    @abstractmethod
    def get(self, path_or_ref: Union[str, "MemoryRef"]) -> Any:
        """Get the value of a (direct or indirect) field or a MemoryRef in the object.

        Parameters
        ----------
        path_or_ref: Union[str,MemoryRef]
          Relative path from the current object to the target field or
          a MemoryRef instance.

        Returns:
        -------
        Any
          If the input is a MemoryRef, resolve the reference and returns the data.
          If the field is a direct field, return the concrete data stored.
          If the field is an indirect field, another MemoryObject will be returned.
          If the field doesn't exist, return None.
        """

    @abstractmethod
    def set(self, path: str, value: Any) -> "MemoryRef":
        """Set the value of a direct field in the object and return a reference to it.
        This will also create the intermediate objects if not exist.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.
        value: Any
          New value of the field. The type of the value must be a primary type.

        Returns:
        -------
        MemoryRef
          A newly created reference to the data just set.
        """

    @abstractmethod
    def new_object(self, path: str) -> "MemoryObject":
        """Create a new object as the value of an indirect field in the object.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.

        Returns:
        -------
        MemoryObject
          The created object.
        """

    @abstractmethod
    def is_exist(self, path: str) -> bool:
        """Check whether a (direct or indirect) field exist in the object.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.

        Returns:
        -------
        bool
          Whether the field exists.
        """

    @abstractmethod
    def get_field_names(self) -> List[str]:
        """Get names of all the top-level subfields of the object.

        Returns:
        -------
        List[str]
          Top-level subfield names of the object in a list.
        """

    @abstractmethod
    def get_fields(self) -> Dict[str, Any]:
        """Get all the top-level subfields of the object.

        Returns:
        -------
        Dict[str, Any]
          Top-level subfields of the object in a dictionary.
        """
