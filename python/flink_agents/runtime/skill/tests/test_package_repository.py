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
"""Unit tests for PackageSkillRepository."""

import importlib
import shutil
import sys
import zipfile
from pathlib import Path

import pytest

from flink_agents.runtime.skill.repository.package_repository import (
    PackageSkillRepository,
)


def _zip_dir(src: Path, dst_zip: Path) -> None:
    with zipfile.ZipFile(dst_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in src.rglob("*"):
            if path.is_file():
                zf.write(path, arcname=path.relative_to(src))


@pytest.fixture
def installed_pkg(tmp_path: Path) -> str:
    """Create a tmp Python package on sys.path with skills as both a directory
    and a zip resource. Yields the package name.
    """
    pkg_root = tmp_path / "pkg_root"
    pkg_root.mkdir()
    pkg_name = "_flink_agents_test_skills_pkg"
    pkg_dir = pkg_root / pkg_name
    pkg_dir.mkdir()
    (pkg_dir / "__init__.py").write_text("")

    # Copy the existing skills/ directory into the package as a directory resource.
    src_skills = Path(__file__).parent / "resources" / "skills"
    shutil.copytree(src_skills, pkg_dir / "skills")

    # Build a zip resource alongside the directory.
    _zip_dir(src_skills, pkg_dir / "skills.zip")

    sys.path.insert(0, str(pkg_root))
    importlib.invalidate_caches()
    try:
        yield pkg_name
    finally:
        sys.path.remove(str(pkg_root))
        # Remove cached module so it doesn't leak across tests.
        sys.modules.pop(pkg_name, None)


class TestPackageSkillRepository:
    def test_load_directory_resource(self, installed_pkg: str) -> None:
        repo = PackageSkillRepository(installed_pkg, "skills")

        names = {s.name for s in repo.get_skills()}
        assert names == {"github", "nano-banana-pro"}

    def test_load_zip_resource(self, installed_pkg: str) -> None:
        repo = PackageSkillRepository(installed_pkg, "skills.zip")

        names = {s.name for s in repo.get_skills()}
        assert names == {"github", "nano-banana-pro"}

    def test_missing_resource(self, installed_pkg: str) -> None:
        with pytest.raises(ValueError, match="not found in package"):
            PackageSkillRepository(installed_pkg, "no_such_resource")
