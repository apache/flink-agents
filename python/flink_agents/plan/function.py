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

import logging
import importlib
import inspect
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, List, Tuple, get_type_hints, Generator

from pydantic import BaseModel

from flink_agents.plan.utils import check_type_match

# Global cache for PythonFunction instances to avoid repeated creation
_PYTHON_FUNCTION_CACHE: Dict[Tuple[str, str], "PythonFunction"] = {}

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


def _is_function_cacheable(func: Callable) -> bool:
    """Check if a function is safe to cache.

    Returns False for functions that should not be cached due to:
    - Closures (functions that capture variables from outer scope)
    - Generator functions (functions that yield values)
    - Functions with mutable default arguments
    - Instance methods (which depend on instance state)

    Parameters
    ----------
    func : Callable
        The function to check

    Returns:
    -------
    bool
        True if the function is safe to cache, False otherwise
    """
    if func is None:
        return False

    if inspect.isgeneratorfunction(func):
        return False

    if inspect.iscoroutinefunction(func):
        return False

    if inspect.ismethod(func):
        return False

    if hasattr(func, "__closure__") and func.__closure__ is not None:
        return False

    # Check for mutable default arguments
    try:
        sig = inspect.signature(func)
        for param in sig.parameters.values():
            if param.default is not inspect.Parameter.empty:
                # Check if default is a mutable type
                if isinstance(param.default, (list, dict, set)):
                    return False

                if param.default is None:
                    try:
                        source = inspect.getsource(func)
                        param_name = param.name
                        if f"if {param_name} is None:" in source and any(
                            mutable_type in source
                            for mutable_type in [
                                "[]",
                                "{}",
                                "list()",
                                "dict()",
                                "set()",
                            ]
                        ):
                            return False
                    except (OSError, TypeError):
                        pass
    except (ValueError, TypeError):
        return False

    return True


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
    __is_cacheable: bool = None

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
        func = self.__get_func()
        params = inspect.signature(func).parameters

        # Use get_type_hints to resolve string annotations properly
        try:
            type_hints = get_type_hints(func)
            annotations = [
                type_hints.get(param_name, param.annotation)
                for param_name, param in params.items()
            ]
        except (NameError, AttributeError):
            # Fallback to raw annotations if get_type_hints fails
            annotations = [param.annotation for param in params.values()]

        err_msg = (
            f"Expect {self.qualname} have signature {args}, but got {annotations}."
        )
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
            # TODO: support function of inner class.
            if "." in self.qualname:
                # Handle class methods (e.g., 'ClassName.method')
                classname, methodname = self.qualname.rsplit(".", 1)
                clazz = getattr(module, classname)
                self.__func = getattr(clazz, methodname)
            else:
                # Handle standalone functions
                self.__func = getattr(module, self.qualname)
        return self.__func

    def is_cacheable(self) -> bool:
        """Check if this function is cacheable, caching the result for future calls."""
        if self.__is_cacheable is None:
            self.__is_cacheable = _is_function_cacheable(self.__get_func())
        return self.__is_cacheable


# TODO: Implement JavaFunction.
class JavaFunction(Function):
    """Descriptor for a java callable function."""

    qualname: str
    method_name: str
    parameter_types: List[str]

    def __call__(self, *args: Tuple[Any, ...], **kwargs: Dict[str, Any]) -> Any:
        """Execute the stored function with provided arguments."""

    def check_signature(self, *args: Tuple[Any, ...]) -> None:
        """Check function signature is legal or not."""


class PythonGeneratorWrapper:
    """
    A temporary wrapper class for Python generators to work around a
    known issue in PEMJA, where the generator type is incorrectly handled.

    TODO: This wrapper is intended to be a temporary solution. Once PEMJA
    version 0.5.5 (or later) fixes the bug related to generator type conversion,
    this wrapper should be removed. For more details, please refer to
    https://github.com/apache/flink-agents/issues/83.
    """

    def __init__(self, generator: Generator) -> None:
        """Initialize a PythonGeneratorWrapper. """
        self.generator = generator

    def __str__(self) -> str:
        return "PythonGeneratorWrapper, generator=" + str(self.generator)

    def __next__(self) -> Any:
        return next(self.generator)


def call_python_function(module: str, qualname: str, func_args: Tuple[Any, ...]) -> Any:
    """Used to call a Python function in the Pemja environment.

    Uses selective caching to reuse PythonFunction instances for identical
    (module, qualname) pairs to improve performance during frequent invocations.
    Only caches functions that are safe to cache (no closures, generators, etc.).

    Parameters
    ----------
    module : str
        Name of the Python module where the function is defined.
    qualname : str
        Qualified name of the function (e.g., 'ClassName.method' for class methods).
    func_args : Tuple[Any, ...]
        Arguments to pass to the function.

    Returns:
    -------
    Any
        The result of calling the function with the provided arguments.
    """
    cache_key = (module, qualname)

    python_func = None

    if cache_key not in _PYTHON_FUNCTION_CACHE:
        python_func = PythonFunction(module=module, qualname=qualname)
        if python_func.is_cacheable():
            _PYTHON_FUNCTION_CACHE[cache_key] = python_func
    else:
        python_func = _PYTHON_FUNCTION_CACHE[cache_key]

    func_result = python_func(*func_args)
    if isinstance(func_result, Generator):
        return PythonGeneratorWrapper(func_result)
    return func_result


def clear_python_function_cache() -> None:
    """Clear the PythonFunction cache.

    This function is useful for testing or when you want to ensure
    fresh function instances are created.
    """
    global _PYTHON_FUNCTION_CACHE
    _PYTHON_FUNCTION_CACHE.clear()


def get_python_function_cache_size() -> int:
    """Get the current size of the PythonFunction cache.

    Returns:
    -------
    int
        The number of cached PythonFunction instances.
    """
    return len(_PYTHON_FUNCTION_CACHE)


def get_python_function_cache_keys() -> List[Tuple[str, str]]:
    """Get all cache keys (module, qualname) pairs currently in the cache.

    Returns:
    -------
    List[Tuple[str, str]]
        List of (module, qualname) tuples representing cached functions.
    """
    return list(_PYTHON_FUNCTION_CACHE.keys())


def call_python_generator(generator_wrapper: PythonGeneratorWrapper) -> (bool, Any):
    """
    Invokes the next step of a wrapped Python generator and returns whether
    it is done, along with the yielded or returned value.

    Args:
        generator_wrapper (PythonGeneratorWrapper): A wrapper object that
        contains a `generator` attribute. This attribute should be an instance
        of a Python generator.

    Returns:
        Tuple[bool, Any]:
            - The first element is a boolean flag indicating whether the generator
            has finished:
                * False: The generator has more values to yield.
                * True: The generator has completed.
            - The second element is either:
                * The value yielded by the generator (when not exhausted), or
                * The return value of the generator (when it has finished).
    """
    try:
        result = next(generator_wrapper.generator)
    except StopIteration as e:
        return True, e.value if hasattr(e, 'value') else None
    except Exception:
        logger.exception("Error in generator execution")
        raise
    else:
        return False, result
