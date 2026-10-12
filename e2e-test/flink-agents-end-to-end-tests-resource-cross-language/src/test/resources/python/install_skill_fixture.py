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
"""Install a self-contained Skill wheel into a test-owned target directory."""

import subprocess
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
package = sys.argv[2]
wheel = root / "issue1194_skills-1.0-py3-none-any.whl"
with zipfile.ZipFile(wheel, "w") as archive:
    archive.writestr(f"{package}/__init__.py", "")
    archive.writestr(
        f"{package}/skills/demo/SKILL.md",
        "---\nname: demo\ndescription: Package skill\n---\nInstructions from wheel",
    )
    archive.writestr(
        f"{package}/skills/demo/references/example.txt", "wheel-attachment"
    )
    archive.writestr(
        "issue1194_skills-1.0.dist-info/METADATA",
        "Metadata-Version: 2.1\nName: issue1194-skills\nVersion: 1.0\n",
    )
    archive.writestr(
        "issue1194_skills-1.0.dist-info/WHEEL",
        "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
    )
    archive.writestr("issue1194_skills-1.0.dist-info/RECORD", "")
subprocess.run(
    [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--no-index",
        "--no-deps",
        "--target",
        str(root / "installed"),
        str(wheel),
    ],
    check=True,
)
