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
################################################################################
"""Verify ResourceName Python paths resolve to existing, importable classes."""

from __future__ import annotations

import importlib
import inspect

import pytest

from flink_agents.api.resource import ResourceName

PYTHON_PREFIX = "flink_agents."


def _collect_python_class_paths() -> list[tuple[str, str]]:
    paths = []
    for resource_name in ["ChatModel", "EmbeddingModel", "VectorStore"]:
        if not hasattr(ResourceName, resource_name):
            continue
        resource_cls = getattr(ResourceName, resource_name)
        for attr in dir(resource_cls):
            if attr.startswith("_"):
                continue
            val = getattr(resource_cls, attr)
            if isinstance(val, str) and val.startswith(PYTHON_PREFIX):
                paths.append((f"{resource_name}.{attr}", val))
    if hasattr(ResourceName, "MCP_SERVER") and isinstance(ResourceName.MCP_SERVER, str):
        if ResourceName.MCP_SERVER.startswith(PYTHON_PREFIX):
            paths.append(("MCP_SERVER", ResourceName.MCP_SERVER))
    return paths


def _class_exists(full_class_path: str) -> tuple[bool, str]:
    if not full_class_path or "." not in full_class_path:
        return False, "Invalid classpath format"
    last_dot = full_class_path.rindex(".")
    module_path = full_class_path[:last_dot]
    class_name = full_class_path[last_dot + 1 :]
    try:
        module = importlib.import_module(module_path)
        cls = getattr(module, class_name, None)
        if cls is None:
            return (
                False,
                f"module {module_path!r} Attribute does not exist {class_name!r}",
            )
        if not inspect.isclass(cls):
            return False, f"{full_class_path!r} is not a class"
    except Exception as e:
        return False, f"import error: {type(e).__name__}: {e}"
    else:
        return True, ""


_RESOURCE_PATHS = _collect_python_class_paths()


@pytest.mark.parametrize(
    ("path_name", "full_path"),
    _RESOURCE_PATHS,
    ids=[p[0] for p in _RESOURCE_PATHS],
)
def test_resource_name_python_classes_exist(path_name: str, full_path: str) -> None:
    """Each parameterized path must import as a class."""
    exists, err_msg = _class_exists(full_path)
    assert exists, (
        f"ResourceName.{path_name} = {full_path!r} The corresponding class does not exist: {err_msg} "
    )
