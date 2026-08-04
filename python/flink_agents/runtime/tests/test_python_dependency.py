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

import importlib
import shutil
import sys
import uuid
from importlib.resources import files
from pathlib import Path

import pytest

from flink_agents.plan import function as plan_function
from flink_agents.runtime import _python_dependency


@pytest.fixture(autouse=True)
def restore_import_state():
    original_sys_path = list(sys.path)
    original_importer_cache = dict(sys.path_importer_cache)
    original_generations = dict(_python_dependency._JOB_GENERATIONS)
    original_function_cache = dict(plan_function._PYTHON_FUNCTION_CACHE)
    imported_packages: set[str] = set()

    yield imported_packages

    sys.path[:] = original_sys_path
    sys.path_importer_cache.clear()
    sys.path_importer_cache.update(original_importer_cache)
    _python_dependency._JOB_GENERATIONS.clear()
    _python_dependency._JOB_GENERATIONS.update(original_generations)
    plan_function._PYTHON_FUNCTION_CACHE.clear()
    plan_function._PYTHON_FUNCTION_CACHE.update(original_function_cache)
    for package_name in imported_packages:
        for module_name in list(sys.modules):
            if module_name == package_name or module_name.startswith(
                f"{package_name}."
            ):
                sys.modules.pop(module_name, None)
    importlib.invalidate_caches()


def test_generation_change_reloads_user_package_and_resources(
    tmp_path: Path, restore_import_state
):
    package_name = f"generation_package_{uuid.uuid4().hex}"
    restore_import_state.add(package_name)
    old_generation, old_python_path = _create_generation(
        tmp_path, "old", package_name, "old"
    )
    sys.path.insert(0, str(old_python_path))

    assert _python_dependency.ensure_python_dependency_generation(
        "job-1", str(old_generation)
    )
    old_module = importlib.import_module(f"{package_name}.action")
    assert old_module.VALUE == "old"
    plan_function._PYTHON_FUNCTION_CACHE[(f"{package_name}.action", "handler")] = (
        object()
    )
    assert files(package_name).joinpath("skills", "SKILL.md").read_text() == "old"

    shutil.rmtree(old_generation)
    new_generation, new_python_path = _create_generation(
        tmp_path, "new", package_name, "new"
    )
    sys.path.insert(0, str(new_python_path))

    assert _python_dependency.ensure_python_dependency_generation(
        "job-1", str(new_generation)
    )
    assert package_name not in sys.modules
    assert f"{package_name}.action" not in sys.modules
    assert str(old_python_path) not in sys.path
    assert str(old_python_path) not in sys.path_importer_cache
    assert sys.path[0] == str(new_python_path)
    assert plan_function.get_python_function_cache_size() == 0

    new_module = importlib.import_module(f"{package_name}.action")
    assert new_module.VALUE == "new"
    assert files(package_name).joinpath("skills", "SKILL.md").read_text() == "new"

    # Each Pemja interpreter inserts its configured paths before this guard runs.
    sys.path.insert(0, str(new_python_path))
    assert not _python_dependency.ensure_python_dependency_generation(
        "job-1", str(new_generation)
    )
    assert sys.path.count(str(new_python_path)) == 1
    assert importlib.import_module(f"{package_name}.action") is new_module


def test_generation_change_preserves_other_active_job(
    tmp_path: Path, restore_import_state
):
    first_package = f"first_package_{uuid.uuid4().hex}"
    second_package = f"second_package_{uuid.uuid4().hex}"
    restore_import_state.update({first_package, second_package})

    first_old_generation, first_old_python_path = _create_generation(
        tmp_path, "first-old", first_package, "first-old"
    )
    second_generation, second_python_path = _create_generation(
        tmp_path, "second", second_package, "second"
    )
    sys.path.insert(0, str(first_old_python_path))

    _python_dependency.ensure_python_dependency_generation(
        "job-1", str(first_old_generation)
    )
    first_old_module = importlib.import_module(f"{first_package}.action")

    sys.path.insert(0, str(second_python_path))
    _python_dependency.ensure_python_dependency_generation(
        "job-2", str(second_generation)
    )
    second_module = importlib.import_module(f"{second_package}.action")

    shutil.rmtree(first_old_generation)
    first_new_generation, first_new_python_path = _create_generation(
        tmp_path, "first-new", first_package, "first-new"
    )
    sys.path.insert(0, str(first_new_python_path))
    _python_dependency.ensure_python_dependency_generation(
        "job-1", str(first_new_generation)
    )

    assert first_old_module.VALUE == "first-old"
    assert first_package not in sys.modules
    assert importlib.import_module(f"{first_package}.action").VALUE == "first-new"
    assert importlib.import_module(f"{second_package}.action") is second_module
    assert str(second_python_path) in sys.path


def test_failed_refresh_is_retried(tmp_path: Path, restore_import_state, monkeypatch):
    package_name = f"retry_package_{uuid.uuid4().hex}"
    restore_import_state.add(package_name)
    old_generation, old_python_path = _create_generation(
        tmp_path, "retry-old", package_name, "old"
    )
    sys.path.insert(0, str(old_python_path))
    _python_dependency.ensure_python_dependency_generation(
        "job-retry", str(old_generation)
    )
    importlib.import_module(f"{package_name}.action")

    shutil.rmtree(old_generation)
    new_generation, new_python_path = _create_generation(
        tmp_path, "retry-new", package_name, "new"
    )
    sys.path.insert(0, str(new_python_path))
    original_invalidate_caches = importlib.invalidate_caches

    def fail_invalidate_caches() -> None:
        msg = "injected cache invalidation failure"
        raise RuntimeError(msg)

    monkeypatch.setattr(
        _python_dependency.importlib,
        "invalidate_caches",
        fail_invalidate_caches,
    )
    with pytest.raises(RuntimeError, match="injected cache invalidation failure"):
        _python_dependency.ensure_python_dependency_generation(
            "job-retry", str(new_generation)
        )

    assert _python_dependency._JOB_GENERATIONS["job-retry"] == str(old_generation)

    monkeypatch.setattr(
        _python_dependency.importlib,
        "invalidate_caches",
        original_invalidate_caches,
    )
    assert _python_dependency.ensure_python_dependency_generation(
        "job-retry", str(new_generation)
    )
    assert importlib.import_module(f"{package_name}.action").VALUE == "new"


def _create_generation(
    tmp_path: Path, generation_name: str, package_name: str, value: str
) -> tuple[Path, Path]:
    generation = tmp_path / f"python-dist-{generation_name}"
    python_path = generation / "python-files" / "user-code"
    package_path = python_path / package_name
    skills_path = package_path / "skills"
    skills_path.mkdir(parents=True)
    (package_path / "__init__.py").write_text("")
    (package_path / "action.py").write_text(f"VALUE = {value!r}\n")
    (skills_path / "SKILL.md").write_text(value)
    return generation, python_path
