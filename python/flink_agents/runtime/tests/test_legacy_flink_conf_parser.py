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
from pathlib import Path

import pytest

from flink_agents.runtime.legacy_flink_conf_parser import parse_legacy_flink_conf


def _parse(tmp_path: Path, *lines: str) -> dict[str, str]:
    conf_file = tmp_path / "flink-conf.yaml"
    conf_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return parse_legacy_flink_conf(conf_file)


def test_duplicate_key_overrides_earlier_value(tmp_path):
    assert _parse(tmp_path, "key: first", "key: second") == {"key": "second"}


def test_value_containing_colon_space_is_kept_whole(tmp_path):
    assert _parse(tmp_path, "key: a: b: c") == {"key": "a: b: c"}


def test_comments_are_dropped(tmp_path):
    conf = _parse(
        tmp_path,
        "# full-line comment",
        "key: value # trailing comment",
        "other: a#b",
    )

    assert conf == {"key": "value", "other": "a"}


def test_lines_without_key_value_pair_are_skipped(tmp_path, caplog):
    conf = _parse(tmp_path, "key:value", "nested:", "key-only", "valid: yes")

    assert conf == {"valid": "yes"}
    assert "missing space after ':'" in caplog.text


def test_empty_key_or_value_is_skipped(tmp_path, caplog):
    conf = _parse(
        tmp_path, ": value", "key: ", "key2:   # only a comment", "valid: yes"
    )

    assert conf == {"valid": "yes"}
    assert "Key or value was empty" in caplog.text


def test_keys_and_values_are_trimmed_and_kept_as_strings(tmp_path):
    conf = _parse(
        tmp_path,
        "  key   :   1  ",
        "flag: true",
        "quoted: 'v'",
        "list: [a, b]",
    )

    assert conf == {
        "key": "1",
        "flag": "true",
        "quoted": "'v'",
        "list": "[a, b]",
    }


def test_indented_lines_are_read_as_top_level_keys(tmp_path):
    assert _parse(tmp_path, "agent:", "    key1: v1") == {"key1": "v1"}


def test_missing_file_fails(tmp_path):
    with pytest.raises(FileNotFoundError):
        parse_legacy_flink_conf(tmp_path / "missing.yaml")
