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

import importlib
import inspect
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Tuple

from pydantic import BaseModel

from flink_agents.plan.utils import check_type_match


class Function(BaseModel, ABC):
    """Base interface for user defined functions, includes python and java."""

    @abstractmethod
    def check_signature(self, *args: Tuple[Any, ...]) -> None:
        """Check function signature is legal or not."""

    @abstractmethod
    def __call__(self, *args: Tuple[Any, ...], **kwargs: Dict[str, Any]) -> Any:
        """Execute function."""

class PythonFunction(Function):
    """Descriptor for a python callable function, storing module and qualified name for
    dynamic retrieval.

    This class allows serialization and lazy loading of functions by storing their
    module and qualified name. The actual callable is loaded on-demand when the instance
    is called.

    Attributes:
    ----------
    module : str
        Name of the Python module where the function is defined.
    qualname : str
        Qualified name of the function (e.g., 'ClassName.method' for class methods).
    """

    module: str
    qualname: str
    __func: Callable = None

    @staticmethod
    def from_callable(func: Callable) -> Function:
        """Create a Function descriptor from an existing callable.

        Parameters
        ----------
        func : Callable
            The function or method to be wrapped.

        Returns:
        -------
        Function
            A Function instance with module and qualname populated based on the input
            callable.
        """
        return PythonFunction(
            module=inspect.getmodule(func).__name__,
            qualname=func.__qualname__,
            __func=func,
        )

    def check_signature(self, *args: Tuple[Any, ...]) -> None:
        """Check function signature."""
        params = inspect.signature(self.__get_func()).parameters
        annotations = [param.annotation for param in params.values()]
        err_msg = f"Expect {self.qualname} have signature {args}, but got {annotations}."
        if len(params) != len(args):
            raise TypeError(err_msg)
        try:
            for i, annotation in enumerate(annotations):
                check_type_match(annotation, args[i])
        except TypeError as e:
            raise TypeError(err_msg) from e


    def __call__(self, *args: Tuple[Any, ...], **kwargs: Dict[str, Any]) -> Any:
        """Execute the stored function with provided arguments.

        Lazily loads the function from its module and qualified name if not already
        cached.

        Parameters
        ----------
        *args : tuple
            Positional arguments to pass to the function.
        **kwargs : dict
            Keyword arguments to pass to the function.

        Returns:
        -------
        Any
            The result of calling the resolved function with the provided arguments.

        Notes:
        -----
        If the function is a method (qualified name contains a class reference), it will
        resolve the method from the corresponding class.
        """
        return self.__get_func()(*args, **kwargs)

    def __get_func(self) -> Callable:
        if self.__func is None:
            module = importlib.import_module(self.module)
            #TODO: support function of inner class.
            if "." in self.qualname:
                # Handle class methods (e.g., 'ClassName.method')
                classname, methodname = self.qualname.rsplit(".", 1)
                clazz = getattr(module, classname)
                self.__func = getattr(clazz, methodname)
            else:
                # Handle standalone functions
                self.__func = getattr(module, self.qualname)
        return self.__func


#TODO: Implement JavaFunction.
class JavaFunction(Function):
    """Descriptor for a java callable function."""

    def __call__(self, *args: Tuple[Any, ...], **kwargs: Dict[str, Any]) -> Any:
        """Execute the stored function with provided arguments."""

    def check_signature(self, *args: Tuple[Any, ...]) -> None:
        """Check function signature is legal or not."""


def call_python_function(module: str, qualname: str, func_args: Tuple[Any, ...]) -> Any:
    """Used to call a Python function in the Pemja environment."""
    func = PythonFunction(module=module, qualname=qualname)
    return func(*func_args)
