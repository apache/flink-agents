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
from typing import Any, Callable, Dict, Tuple

from pydantic import BaseModel


class Function(BaseModel):
    """Descriptor for a callable function, storing module and qualified name for dynamic
    retrieval.

    This class allows serialization and lazy loading of functions by storing their
    module and
    qualified name. The actual callable is loaded on-demand when the instance is called.

    Attributes:
    ----------
    module : str
        Name of the Python module where the function is defined.
    qualname : str
        Qualified name of the function (e.g., 'ClassName.method' for class methods).
    __func: Callable
        Internal cache for the resolved function
    """

    module: str
    qualname: str
    __func: Callable = None

    @staticmethod
    def from_callable(func: Callable) -> "Function":
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
        return Function(
            module=inspect.getmodule(func).__name__,
            qualname=func.__qualname__,
            __func=func,
        )

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
        if self.__func is None:
            module = importlib.import_module(self.module)
            if "." in self.qualname:
                # Handle class methods (e.g., 'ClassName.method')
                classname, methodname = self.qualname.rsplit(".", 1)
                clazz = getattr(module, classname)
                self.__func = getattr(clazz, methodname)
            else:
                # Handle standalone functions
                self.__func = getattr(module, self.qualname)
        return self.__func(*args, **kwargs)
