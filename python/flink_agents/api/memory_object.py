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
from typing import TYPE_CHECKING, Any, Dict, List, Union

from pydantic import BaseModel

if TYPE_CHECKING:
    from flink_agents.api.memory_reference import MemoryRef

class MemoryObject(BaseModel, ABC):
    """Representation of an object in the short-term memory.

    A direct field is a field which stores concrete data directly, while an indirect
    filed is just a field which represents a nested object. Fields can be accessed
    using an absolute or relative path.
    """

    @abstractmethod
    def get(self, path_or_ref: Union[str,"MemoryRef"] ) -> Any:
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
