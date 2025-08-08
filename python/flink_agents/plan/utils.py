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
import typing
from typing import Any


def check_type_match(actual: Any, expect: Any) -> None:
    """Check if the actual type can match the expect type.

    Parameters
    ----------
    actual : class or generic type
        The actual type to check.
    expect : class or generic type
        The expect type to satisfy.
    """
    # if expect type is Any, all actual type is legal.
    if expect is Any:
        return

    # Handle string annotations from __future__ import annotations
    if isinstance(actual, str):
        # For string annotations, we need to compare the string representation
        # Convert expect type to string for comparison
        if hasattr(expect, "__name__"):
            expect_str = expect.__name__
        else:
            expect_str = str(expect)

        # Simple string comparison for basic types
        if actual == expect_str or actual == str(expect):
            return

        # For complex types, try to extract the base type name
        if "[" in actual:
            actual_base = actual.split("[")[0]
            if hasattr(expect, "__name__"):
                expect_base = expect.__name__
            elif hasattr(expect, "_name"):
                expect_base = expect._name
            else:
                expect_base = str(expect).split("[")[0]

            if actual_base == expect_base:
                return

        # If string comparison fails, raise TypeError
        raise TypeError()

    actual_class = actual
    expect_class = expect
    # get origin class from generic type.
    if typing.get_origin(actual) is not None:
        actual_class = typing.get_origin(actual)
    if typing.get_origin(expect) is not None:
        expect_class = typing.get_origin(expect)

    # check Ellipsis(...)
    if expect is Ellipsis and actual is Ellipsis:
        return

    if not issubclass(actual_class, expect_class):
        raise TypeError()

    # Check type arguments when expect type has.
    expect_args = typing.get_args(expect)
    if expect_args is not None and len(expect_args) != 0:
        if len(typing.get_args(actual)) != len(typing.get_args(expect)):
            raise TypeError()
        for actual_arg, expect_arg in zip(
            typing.get_args(actual), typing.get_args(expect)
        ):
            check_type_match(actual_arg, expect_arg)
