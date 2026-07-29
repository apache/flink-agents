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
"""Tests for the Skills resource API."""

from flink_agents.api.skills import Skills, SkillSourceSpec


class TestSkillsFactories:
    def test_from_local_dir_emits_local_scheme(self) -> None:
        s = Skills.from_local_dir("/a", "/b.zip")
        assert s.sources == [
            SkillSourceSpec(scheme="local", params={"path": "/a"}),
            SkillSourceSpec(scheme="local", params={"path": "/b.zip"}),
        ]

    def test_from_url_emits_url_scheme(self) -> None:
        s = Skills.from_url("https://example.com/x.zip")
        assert s.sources == [
            SkillSourceSpec(
                scheme="url", params={"url": "https://example.com/x.zip"}
            )
        ]

    def test_from_package_single_pair(self) -> None:
        s = Skills.from_package(("my_pkg", "skills"))
        assert s.sources == [
            SkillSourceSpec(
                scheme="package", params={"package": "my_pkg", "resource": "skills"}
            )
        ]

    def test_from_package_varargs(self) -> None:
        s = Skills.from_package(("pkg_a", "skills"), ("pkg_b", "other"))
        assert s.sources == [
            SkillSourceSpec(
                scheme="package", params={"package": "pkg_a", "resource": "skills"}
            ),
            SkillSourceSpec(
                scheme="package", params={"package": "pkg_b", "resource": "other"}
            ),
        ]

    def test_serialize_roundtrip(self) -> None:
        s = Skills(
            sources=[
                SkillSourceSpec(scheme="local", params={"path": "/a"}),
                SkillSourceSpec(
                    scheme="url", params={"url": "https://e.com/x.zip"}
                ),
                SkillSourceSpec(
                    scheme="package",
                    params={"package": "p", "resource": "skills"},
                ),
            ]
        )
        dumped = s.model_dump()
        restored = Skills.model_validate(dumped)
        assert restored.sources == s.sources


class TestSkillSourceSpec:
    def test_scheme_is_lowercased(self) -> None:
        spec = SkillSourceSpec(scheme="LOCAL", params={"path": "/x"})
        assert spec.scheme == "local"

    def test_equality_ignores_scheme_case(self) -> None:
        a = SkillSourceSpec(scheme="LOCAL", params={"path": "/x"})
        b = SkillSourceSpec(scheme="local", params={"path": "/x"})
        assert a == b

    def test_hashable(self) -> None:
        a = SkillSourceSpec(scheme="local", params={"path": "/x"})
        b = SkillSourceSpec(scheme="LOCAL", params={"path": "/x"})
        assert hash(a) == hash(b)
        # Spec is usable as a set / dict key, supporting de-duplication during merge.
        assert len({a, b}) == 1

    def test_unknown_scheme_deserializes_successfully(self) -> None:
        # The registry — not the model — is the fail point.
        spec = SkillSourceSpec.model_validate(
            {"scheme": "future-scheme", "params": {"k": "v"}}
        )
        assert spec.scheme == "future-scheme"
        assert spec.params == {"k": "v"}
