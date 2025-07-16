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
from typing import Any, Dict, List

from pydantic import BaseModel


class MemoryObject(BaseModel, ABC):
    """Representation of an object in the short-term memory.

    A direct field is a field which stores concrete data directly, while an indirect
    filed is just a field which represents a nested object. Fields can be accessed
    using an absolute or relative path.
    """

    @abstractmethod
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

    @abstractmethod
    def set(self, path: str, value: Any) -> None:
        """Set the value of an indirect field in the object.
        This will also create the intermediate objects if not exist.

        Parameters
        ----------
        path: str
          Relative path from the current object to the target field.
        value: Any
          New value of the field. The type of the value must be a primary type.
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
