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
from typing import Any, Dict, List

from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.memory_reference import MemoryRef


class FlinkMemoryObject(MemoryObject):
    """Python wrapper for Java's MemoryObjectImpl, providing access to short-term
    memory in Flink environment.

    This class allows Python functions to interact with the Flink-based short-term
    memory implemented in Java.
    """

    def __init__(self, j_memory_object: Any) -> None:
        """Initialize with a Java MemoryObject instance."""
        self._j_memory_object = j_memory_object

    def get(self, path_or_ref: str | MemoryRef) -> Any:
        """Get a nested object or value by path or MemoryRef.

        If the input is a MemoryRef, resolve the reference and return the data.
        If the field is a direct field, return the concrete data stored.
        If the field is an indirect object, return a new FlinkMemoryObject.
        Return None if the field does not exist.
        """
        try:
            path_to_get: str
            if isinstance(path_or_ref, MemoryRef):
                path_to_get = path_or_ref.path
            elif isinstance(path_or_ref, str):
                path_to_get = path_or_ref
            j_result = self._j_memory_object.get(path_to_get)
            if j_result is None:
                return None
            if j_result.isNestedObject():
                return FlinkMemoryObject(j_result)
            else:
                return j_result.getValue()
        except Exception as e:
            msg = f"Failed to get field '{path_or_ref}' from short-term memory"
            raise MemoryObjectError(msg) from e

    def set(self, path: str, value: Any) -> MemoryRef:
        """Set a value at the given path. Creates intermediate objects if needed."""
        try:
            j_ref = self._j_memory_object.set(path, value)
            return MemoryRef(path=j_ref.getPath())
        except Exception as e:
            msg = f"Failed to set value at path '{path}'"
            raise MemoryObjectError(msg) from e

    def new_object(self, path: str, *, overwrite: bool = False) -> "FlinkMemoryObject":
        """Create a new object at the given path."""
        try:
            return FlinkMemoryObject(self._j_memory_object.newObject(path, overwrite))
        except Exception as e:
            msg = f"Failed to create new object at path '{path}'"
            raise MemoryObjectError(msg) from e

    def is_exist(self, path: str) -> bool:
        """Check if a field exists at the given path."""
        try:
            return self._j_memory_object.isExist(path)
        except Exception as e:
            msg = f"Failed to check existence of '{path}'"
            raise MemoryObjectError(msg) from e

    def get_field_names(self) -> List[str]:
        """Get names of all direct fields in the current object."""
        try:
            return list(self._j_memory_object.getFieldNames())
        except Exception as e:
            msg = "Failed to get field names"
            raise MemoryObjectError(msg) from e

    def get_fields(self) -> Dict[str, Any]:
        """Get all direct fields and their values."""
        try:
            return dict(self._j_memory_object.getFields())
        except Exception as e:
            msg = "Failed to get fields"
            raise MemoryObjectError(msg) from e


class MemoryObjectError(RuntimeError):
    """All errors raised by FlinkMemoryObject wrapper are gathered in."""
