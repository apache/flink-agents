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
from typing import Any, ClassVar, Dict, List

from flink_agents.api.memoryobject import MemoryObject


class LocalMemoryObject(MemoryObject):
    """LocalMemoryObject: Flattened hierarchical key-value store for local
    python execution.

    Each object keeps a prefix to represent its logical path to a flattened
    key in the store.
    """

    ROOT_KEY: ClassVar[str] = ""
    SEPARATOR: ClassVar[str] = "."
    NESTED_MARK: ClassVar[str] = "NestedObject"

    _store: dict[str, Any]
    _prefix: str

    def __init__(self, store: Dict[str, Any], prefix: str = ROOT_KEY) -> None:
        """Initialize a LocalMemoryObject.

        Parameters
        ----------
        store : Dict[str, Any]
            The dictionary used as the underlying storage.
        prefix : str, default ROOT_KEY
            Path prefix that identifies the current position of the object in the
            shared store.
        """
        super().__init__()
        self._store = store if store is not None else {}
        self._prefix = prefix

        if self.ROOT_KEY not in self._store:
            self._store[self.ROOT_KEY] = _ObjMarker()

    def get(self, path: str) -> Any:
        """Get the value of a (direct or indirect) field in the object.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.

        Returns:
        -------
        Any
          If the field is a direct field, returns the concrete data stored.
          If the field is an indirect field, another MemoryObject will be returned.
          If the field doesn't exist, returns None.
        """
        abs_path = self._full_path(path)
        if abs_path in self._store:
            value = self._store[abs_path]
            if self._is_nested_object(value):
                return LocalMemoryObject(self._store, abs_path)
            return value
        return None

    def set(self, path: str, value: Any) -> None:
        """Set the value of a (direct or indirect) field in the object.
        This will also create the intermediate objects if not exist.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.
        value: Any
          New value of the field.
          The type of the value must be either a primary type, or MemoryObject.
        """
        if isinstance(value, LocalMemoryObject):
            msg = "Do not set a MemoryObject instance directly; use new_object()."
            raise TypeError(msg)

        abs_path = self._full_path(path)
        parts = abs_path.split(self.SEPARATOR)

        self._fill_parents(parts)

        parent_path = (
            self.SEPARATOR.join(parts[:-1]) if len(parts) > 1 else self.ROOT_KEY
        )
        self._add_subfield(parent_path, parts[-1])

        if abs_path in self._store and self._is_nested_object(self._store[abs_path]):
            msg = f"Cannot overwrite object field '{abs_path}' with primitive."
            raise ValueError(msg)

        self._store[abs_path] = value

    def new_object(self, path: str, *, overwrite: bool = False) -> "LocalMemoryObject":
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
        abs_path = self._full_path(path)
        parts = abs_path.split(self.SEPARATOR)

        self._fill_parents(parts)

        parent_path = (
            self.SEPARATOR.join(parts[:-1]) if len(parts) > 1 else self.ROOT_KEY
        )
        self._add_subfield(parent_path, parts[-1])

        if abs_path in self._store and not self._is_nested_object(
            self._store[abs_path]
        ):
            if not overwrite:
                msg = f"Field '{abs_path}' exists but is not object."
                raise ValueError(msg)

        self._store[abs_path] = _ObjMarker()
        return LocalMemoryObject(self._store, abs_path)

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
        return self._full_path(path) in self._store

    def get_field_names(self) -> List[str]:
        """Get names of all the direct fields of the object.

        Returns:
        -------
        List[str]
          Direct field names of the object in a list.
        """
        marker = self._store.get(self._prefix)
        if self._is_nested_object(marker):
            return sorted(marker.subfields)
        return []

    def get_fields(self) -> Dict[str, Any]:
        """Get all the direct fields of the object.

        Returns:
        -------
        Dict[str, Any]
          Direct fields in a dictionary.
        """
        result = {}
        for name in self.get_field_names():
            abs_path = self._full_path(name)
            value = self._store[abs_path]
            result[name] = self.NESTED_MARK if self._is_nested_object(value) else value
        return result

    def _full_path(self, rel: str) -> str:
        """Convert a relative field path to its absolute flattened key in the store."""
        return f"{self._prefix}.{rel}" if rel else self._prefix

    @staticmethod
    def _is_nested_object(value: Any) -> bool:
        """Check whether the stored value represents a nested object."""
        return isinstance(value, _ObjMarker)

    def _ensure_object_node(self, path: str) -> "_ObjMarker":
        """Ensure the given path exists in store *as an object* and return marker."""
        if path not in self._store or not self._is_nested_object(self._store[path]):
            self._store[path] = _ObjMarker()
        return self._store[path]

    def _add_subfield(self, parent: str, subfield: str) -> None:
        """Add subfield under parent. Parent becomes nested object if needed."""
        self._ensure_object_node(parent).subfields.add(subfield)

    def _fill_parents(self, parts: List[str]) -> None:
        """Ensure all intermediate objects existed."""
        for i in range(1, len(parts)):
            parent_path = (
                self.SEPARATOR.join(parts[: i - 1]) if i > 1 else self.ROOT_KEY
            )
            cur_path = self.SEPARATOR.join(parts[:i])
            self._ensure_object_node(cur_path)
            self._add_subfield(parent_path, parts[i - 1])


class _ObjMarker:
    """Internal marker for an object node.

    subfields : set[str] keeps all subfield names
    """

    __slots__ = ("subfields",)

    def __init__(self) -> None:
        self.subfields: set[str] = set()

    def __repr__(self) -> str:
        return f"_ObjMarker({sorted(self.subfields)})"
