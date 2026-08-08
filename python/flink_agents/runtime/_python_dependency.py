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

from __future__ import annotations

import importlib
import os
import sys
import threading
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Iterator
    from types import ModuleType
    from typing import Any

_GENERATION_LOCK = threading.RLock()
_JOB_GENERATIONS: dict[str, str] = {}


def ensure_python_dependency_generation(job_id: str, generation: str) -> bool:
    """Activate a Flink-managed dependency generation in the Pemja interpreter.

    When Flink replaces a job's temporary dependency directory, remove imports
    owned by the previous directory before user actions or resources are loaded.

    Returns:
        ``True`` when a different generation was activated, otherwise ``False``.
    """
    if not job_id:
        msg = "job_id must not be empty"
        raise ValueError(msg)

    current_generation = _normalize_path(generation)
    if not Path(current_generation).is_dir():
        msg = f"Python dependency generation does not exist: {current_generation}"
        raise RuntimeError(msg)

    with _GENERATION_LOCK:
        previous_generation = _JOB_GENERATIONS.get(job_id)
        if previous_generation == current_generation:
            # Pemja inserts configured paths for every interpreter sharing this
            # generation.
            _deduplicate_and_prepend_paths(
                _paths_for_generation(sys.path, current_generation)
            )
            return False

        if previous_generation is not None:
            _deactivate_generation(previous_generation)

        _activate_generation(current_generation)

        _JOB_GENERATIONS[job_id] = current_generation
        return True


def _normalize_path(path: str | os.PathLike[str]) -> str:
    # Keep Flink's symlink path so imported modules remain attributable to
    # their owning python-dist generation.
    return os.path.normcase(str(Path(path).absolute()))


def _deactivate_generation(generation: str) -> None:
    _clear_python_function_cache()
    _evict_modules_from_generation(generation)
    _remove_paths_from_generation(sys.path, generation)
    _clear_importer_cache(generation)


def _activate_generation(generation: str) -> None:
    _deduplicate_and_prepend_paths(_paths_for_generation(sys.path, generation))
    _clear_importer_cache(generation)
    importlib.invalidate_caches()


def _paths_for_generation(paths: list[str], generation: str) -> list[str]:
    paths = (_try_normalize_path(path) for path in paths)
    return list(
        dict.fromkeys(
            path
            for path in paths
            if path is not None and _path_belongs_to_generation(path, generation)
        )
    )


def _module_paths(module: ModuleType) -> Iterator[Any]:
    spec = getattr(module, "__spec__", None)
    path_values = (
        getattr(module, "__file__", None),
        getattr(module, "__path__", None),
        getattr(spec, "origin", None),
        getattr(spec, "submodule_search_locations", None),
    )
    for value in path_values:
        if isinstance(value, str | bytes | os.PathLike):
            yield value
        elif value is not None:
            try:
                yield from value
            except (TypeError, ValueError):
                continue


def _try_normalize_path(path: Any) -> str | None:
    if not isinstance(path, str | bytes | os.PathLike):
        return None
    try:
        return _normalize_path(os.fsdecode(path))
    except (OSError, TypeError, ValueError):
        return None


def _clear_python_function_cache() -> None:
    function_module = sys.modules.get("flink_agents.plan.function")
    if function_module is not None:
        function_module.clear_python_function_cache()


def _evict_modules_from_generation(generation: str) -> None:
    modules_to_remove = []
    for module_name, module in list(sys.modules.items()):
        if module is not None and any(
            _path_belongs_to_generation(path, generation)
            for path in _module_paths(module)
        ):
            modules_to_remove.append(module_name)

    for module_name in sorted(
        modules_to_remove, key=lambda name: name.count("."), reverse=True
    ):
        sys.modules.pop(module_name, None)


def _remove_paths_from_generation(paths: list[str], generation: str) -> None:
    paths[:] = [
        path for path in paths if not _path_belongs_to_generation(path, generation)
    ]


def _deduplicate_and_prepend_paths(current_paths: list[str]) -> None:
    normalized_current_paths = set(current_paths)
    sys.path[:] = [
        path
        for path in sys.path
        if _try_normalize_path(path) not in normalized_current_paths
    ]
    sys.path[0:0] = current_paths


def _clear_importer_cache(generation: str) -> None:
    for path in list(sys.path_importer_cache):
        if _path_belongs_to_generation(path, generation):
            sys.path_importer_cache.pop(path, None)


def _path_belongs_to_generation(path: Any, generation: str) -> bool:
    normalized = _try_normalize_path(path)
    if normalized is None:
        return False
    candidate = Path(normalized)
    generation_path = Path(generation)
    return candidate == generation_path or generation_path in candidate.parents
