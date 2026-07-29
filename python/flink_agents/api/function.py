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
"""Data-only descriptors for user-defined functions.

These models carry the information needed to *identify* a Python or Java
function: ``module`` and ``qualname`` for Python; declaring class,
method name, and parameter types for Java.
"""

import importlib
import inspect
from abc import ABC
from typing import Any, Callable, List, Tuple

from pydantic import BaseModel, model_serializer

#: Java parameter types of an action method. Action signatures are fixed
#: ``(Event, RunnerContext)``, so callers never have to spell them out. A tuple
#: keeps the shared constant immutable; callers copy it into their own list.
ACTION_PARAMETER_TYPES: Tuple[str, ...] = (
    "org.apache.flink.agents.api.Event",
    "org.apache.flink.agents.api.context.RunnerContext",
)


class Function(BaseModel, ABC):
    """Marker base class for function descriptors. Pure data — has no
    ``__call__`` and no executable behavior.
    """


class PythonFunction(Function):
    """Descriptor for a Python callable: module + qualified name.

    Attributes:
    ----------
    module : str
        Name of the Python module where the function is defined.
    qualname : str
        Qualified name of the function (e.g. ``ClassName.method`` for
        class methods).
    """

    module: str
    qualname: str

    @model_serializer
    def __serialize(self) -> dict[str, Any]:
        return {
            "func_type": self.__class__.__qualname__,
            "module": self.module,
            "qualname": self.qualname,
        }

    @staticmethod
    def from_callable(func: Callable) -> "PythonFunction":
        """Build a ``PythonFunction`` descriptor from a Python callable."""
        return PythonFunction(
            module=inspect.getmodule(func).__name__,
            qualname=func.__qualname__,
        )

    def as_callable(self) -> Callable:
        """Resolve this descriptor to the underlying Python callable.

        Imports the target module and looks up ``qualname``. Pure Python
        reflection — no execution, no JVM. ``ClassName.method`` is split
        and resolved through the class attribute.
        """
        module = importlib.import_module(self.module)
        if "." in self.qualname:
            classname, methodname = self.qualname.rsplit(".", 1)
            clazz = getattr(module, classname)
            return getattr(clazz, methodname)
        return getattr(module, self.qualname)


class JavaFunction(Function):
    """Descriptor for a Java method: class FQN + method name + parameter types.

    Attributes:
    ----------
    qualname : str
        Fully-qualified name of the declaring Java class.
    method_name : str
        The Java method name.
    parameter_types : List[str]
        The Java parameter types, in declaration order.
    """

    qualname: str
    method_name: str
    parameter_types: List[str]

    @classmethod
    def for_action(cls, qualname: str, method_name: str) -> "JavaFunction":
        """Build a descriptor for a Java action, filling the fixed signature.

        Actions always take ``(Event, RunnerContext)``, so ``parameter_types``
        is implied — mirrors the YAML API, which omits it for ``type: java``
        actions. Tools must still pass ``parameter_types`` to the constructor.
        """
        return cls(
            qualname=qualname,
            method_name=method_name,
            parameter_types=list(ACTION_PARAMETER_TYPES),
        )

    @model_serializer
    def __serialize(self) -> dict[str, Any]:
        return {
            "func_type": self.__class__.__qualname__,
            "qualname": self.qualname,
            "method_name": self.method_name,
            "parameter_types": self.parameter_types,
        }
