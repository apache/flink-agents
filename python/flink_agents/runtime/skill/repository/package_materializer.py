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
"""Resolve Python package resources for both Python and Java repositories."""

from __future__ import annotations

import shutil
from importlib.resources import as_file, files
from pathlib import Path
from typing import TYPE_CHECKING

from flink_agents.runtime.skill.repository._materialize import extract_zip_into

if TYPE_CHECKING:
    from importlib.resources.abc import Traversable


def materialize_package(package: str, resource: str, target_dir: str) -> None:
    """Copy a package resource into the caller's directory; ownership stays with caller.

    Traverse directory resources directly so zipped packages also work on
    Python 3.10/3.11, where as_file only supports individual files.
    """
    source = files(package).joinpath(resource)
    target = Path(target_dir)
    if not source.is_dir() and not source.is_file():
        msg = f"Resource {resource!r} not found in package {package!r}"
        raise ValueError(msg)
    if source.is_dir():
        _copy_directory(source, target)
    elif source.is_file() and source.name.lower().endswith(".zip"):
        with as_file(source) as path:
            extract_zip_into(path, target)
    else:
        msg = f"Python package skill resource {package}/{resource} must exist and be a directory or .zip"
        raise ValueError(msg)


def _copy_directory(source: Traversable, target: Path) -> None:
    if isinstance(source, Path):
        shutil.copytree(source, target, dirs_exist_ok=True)
        return
    target.mkdir(parents=True, exist_ok=True)
    for child in source.iterdir():
        destination = target / child.name
        if child.is_dir():
            _copy_directory(child, destination)
        else:
            with child.open("rb") as input_file, destination.open("wb") as output_file:
                shutil.copyfileobj(input_file, output_file)
