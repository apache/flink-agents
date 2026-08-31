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

import pytest

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
            SkillSourceSpec(scheme="url", params={"url": "https://example.com/x.zip"})
        ]

    @pytest.mark.parametrize(
        "url",
        [
            "https://localhost/x.zip",
            "https://127.0.0.1/x.zip",
            "https://[::1]/x.zip",
            "https://[fe80::1%25eth0]/x.zip",
            "https://example.com./x.zip",
            "https://example.com:/x.zip",
            "https://example.com:65535/x.zip",
            "https://999/x.zip",
            "https://1bar/x.zip",
            "https://999./x.zip",
        ],
    )
    def test_from_url_accepts_shared_valid_host_syntax(self, url: str) -> None:
        assert Skills.from_url(url).sources[0].params["url"] == url

    def test_from_url_with_sha256_emits_integrity_param(self) -> None:
        digest = "A" * 64
        s = Skills.from_url_with_sha256("https://example.com/x.zip", digest)
        assert s.sources == [
            SkillSourceSpec(
                scheme="url",
                params={"url": "https://example.com/x.zip", "sha256": digest},
            )
        ]

    def test_from_url_unsafe_requires_explicit_param(self) -> None:
        s = Skills.from_url_unsafe("http://example.com/x.zip")
        assert s.sources[0].params["allow_insecure_http"] == "true"

    def test_from_url_unsafe_with_sha256_emits_both_params(self) -> None:
        digest = "a" * 64
        s = Skills.from_url_unsafe_with_sha256("http://example.com/x.zip", digest)
        assert s.sources[0].params == {
            "url": "http://example.com/x.zip",
            "sha256": digest,
            "allow_insecure_http": "true",
        }

    def test_from_url_rejects_plain_http_by_default(self) -> None:
        with pytest.raises(ValueError, match="disabled by default"):
            Skills.from_url("http://example.com/x.zip")

    def test_from_url_with_sha256_rejects_malformed_digest(self) -> None:
        with pytest.raises(ValueError, match="64 hexadecimal"):
            Skills.from_url_with_sha256("https://example.com/x.zip", "invalid")

    def test_from_url_rejects_unsupported_scheme_clearly(self) -> None:
        url = "ftp://user:password@example.com/x.zip?token=secret#part"
        with pytest.raises(ValueError, match=r"Only HTTP\(S\)") as exc_info:
            Skills.from_url(url)
        assert "password" not in str(exc_info.value)
        assert "secret" not in str(exc_info.value)

    @pytest.mark.parametrize(
        "url",
        [
            "https://:443/x.zip",
            "https://example.com:bad/x.zip",
            "https://example.com:65536/x.zip",
        ],
    )
    def test_from_url_rejects_invalid_host_and_port(self, url: str) -> None:
        with pytest.raises(ValueError, match=r"valid host|valid port"):
            Skills.from_url(url)

    @pytest.mark.parametrize(
        "url",
        [
            "https://%65xample.com/x.zip",
            "https://-example.com/x.zip",
            "https://example-.com/x.zip",
            "https://.example.com/x.zip",
            "https://example..com/x.zip",
            "https://a../x.zip",
            "https://../x.zip",
            "https://999.999.999.999/x.zip",
            "https://127.1/x.zip",
            "https://1.2.3/x.zip",
            "https://foo.123/x.zip",
            "https://foo.1bar/x.zip",
            "https://1.2.3.4.5/x.zip",
            "https://1.2.3./x.zip",
            "https://1.2.3.4./x.zip",
            "https://[v1.foo]/x.zip",
        ],
    )
    def test_from_url_rejects_invalid_hostname_syntax(self, url: str) -> None:
        with pytest.raises(ValueError, match="valid host"):
            Skills.from_url(url)

    @pytest.mark.parametrize(
        "url",
        [
            "https://skill_server/x.zip",
            "https://tést.com/x.zip",
            "https://\N{KELVIN SIGN}.com/x.zip",
        ],
    )
    def test_from_url_rejects_compatibility_sensitive_hosts(self, url: str) -> None:
        with pytest.raises(ValueError, match="valid host"):
            Skills.from_url(url)

    @pytest.mark.parametrize(
        "url",
        [
            "https://example.com/skills%5B1%5D.zip",
            "https://example.com/x.zip?a[0]=1",
            "https://example.com/x.zip#f[1]",
        ],
    )
    def test_from_url_accepts_brackets_outside_raw_path(self, url: str) -> None:
        assert Skills.from_url(url).sources[0].params["url"] == url

    def test_from_url_rejects_user_info_without_leaking_secrets(self) -> None:
        url = "https://user:password@example.com/x.zip?token=secret#part"
        with pytest.raises(ValueError, match="must not include user info") as exc_info:
            Skills.from_url(url)
        assert "password" not in str(exc_info.value)
        assert "secret" not in str(exc_info.value)

    def test_from_url_redacts_opaque_malformed_credentials(self) -> None:
        url = "https:user:password?token=top-secret"
        with pytest.raises(ValueError) as exc_info:
            Skills.from_url(url)
        assert str(exc_info.value).endswith("<redacted>")
        assert "password" not in str(exc_info.value)
        assert "top-secret" not in str(exc_info.value)

    @pytest.mark.parametrize(
        "url",
        [
            "https://exa mple.com/x.zip",
            "https://example.com/%invalid",
            "https://[fe80::1%eth0]/x.zip",
            "https://[::1/x.zip",
            "https://example.com/skills[1].zip",
        ],
    )
    def test_from_url_rejects_malformed_url(self, url: str) -> None:
        with pytest.raises(ValueError, match="Invalid skill URL"):
            Skills.from_url(url)

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
                    scheme="url",
                    params={
                        "url": "http://e.com/x.zip",
                        "sha256": "a" * 64,
                        "allow_insecure_http": "true",
                    },
                ),
                SkillSourceSpec(
                    scheme="package",
                    params={"package": "p", "resource": "skills"},
                ),
            ]
        )
        dumped = s.model_dump()
        assert dumped["sources"][1]["params"]["allow_insecure_http"] == "true"
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
