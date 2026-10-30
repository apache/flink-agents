#!/usr/bin/env python3
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
"""Apply a CI-only shutdown workaround to the installed PyArrow 11 wheel.

Flink 1.20's Beam dependency requires PyArrow <12. The wheel initializes AWS on
import but does not register shutdown. Register its public cleanup function so
pytest and Beam workers finalize AWS before C++ static destruction. This is not
a backport of Arrow's complete lazy-initialization fix (apache/arrow#38364).
Remove this workaround when the Flink 1.20 CI dependency can be upgraded.
"""

from importlib.metadata import distribution

ORIGINAL = "\nelse:\n    initialize_s3()\n\n\ndef __getattr__(name):"
PATCHED = (
    "\nelse:\n    initialize_s3()\n"
    "    # Flink Agents CI: finalize AWS before C++ static destruction.\n"
    "    import atexit\n"
    "    atexit.register(finalize_s3)\n\n\ndef __getattr__(name):"
)


def patch_pyarrow() -> None:
    """Patch only the known wheel layout, without importing native Arrow code."""
    package = distribution("pyarrow")
    if package.version != "11.0.0":
        message = f"Expected PyArrow 11.0.0, found {package.version}"
        raise RuntimeError(message)
    path = package.locate_file("pyarrow/fs.py")
    source = path.read_text(encoding="utf-8")
    if source.count(PATCHED) == 1 and ORIGINAL not in source:
        print(f"PyArrow {package.version} CI shutdown patch verified: {path}")
        return
    if source.count(ORIGINAL) != 1 or PATCHED in source:
        message = f"Unexpected PyArrow fs.py; refusing to patch: {path}"
        raise RuntimeError(message)
    path.write_text(source.replace(ORIGINAL, PATCHED), encoding="utf-8")
    print(f"Applied PyArrow {package.version} CI shutdown patch: {path}")


if __name__ == "__main__":
    patch_pyarrow()
