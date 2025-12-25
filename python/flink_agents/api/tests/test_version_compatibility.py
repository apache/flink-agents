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
from unittest.mock import patch

from flink_agents.api.version_compatibility import (
    FlinkVersionManager,
    _normalize_version,
)


# Tests for _normalize_version function
def test_normalize_three_part_version() -> None:  # noqa: D103
    assert _normalize_version("1.20.3") == "1.20.3"
    assert _normalize_version("2.2.0") == "2.2.0"


def test_normalize_two_part_version() -> None:  # noqa: D103
    assert _normalize_version("2.2") == "2.2.0"
    assert _normalize_version("1.20") == "1.20.0"


def test_normalize_version_with_suffix() -> None:  # noqa: D103
    assert _normalize_version("2.2-SNAPSHOT") == "2.2.0"
    assert _normalize_version("1.20.dev0") == "1.20.0"
    assert _normalize_version("2.0.rc1") == "2.0.0"


def test_normalize_long_version() -> None:  # noqa: D103
    assert _normalize_version("1.20.3.4") == "1.20.3"
    assert _normalize_version("2.2.0.1.5") == "2.2.0"


# Tests for FlinkVersionManager class
def test_version_property_with_flink_installed() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="1.20.3"):
        manager = FlinkVersionManager()
        assert manager.version == "1.20.3"


def test_version_property_without_flink_installed() -> None:  # noqa: D103
    with patch(
        "importlib.metadata.version", side_effect=Exception("Package not found")
    ):
        manager = FlinkVersionManager()
        assert manager.version is None


def test_major_version_property() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="1.20.3"):
        manager = FlinkVersionManager()
        assert manager.major_version == "1.20"

    with patch("importlib.metadata.version", return_value="2.2.0"):
        manager = FlinkVersionManager()
        assert manager.major_version == "2.2"


def test_major_version_with_snapshot() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="2.2.0-SNAPSHOT"):
        manager = FlinkVersionManager()
        assert manager.major_version == "2.2"


def test_major_version_without_flink() -> None:  # noqa: D103
    with patch(
        "importlib.metadata.version", side_effect=Exception("Package not found")
    ):
        manager = FlinkVersionManager()
        assert manager.major_version is None


def test_ge_method() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="1.20.3"):
        manager = FlinkVersionManager()
        assert manager.ge("1.20.0") is True
        assert manager.ge("1.20.3") is True
        assert manager.ge("1.21.0") is False


def test_ge_with_two_part_version() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="2.2"):
        manager = FlinkVersionManager()
        assert manager.ge("2.0.0") is True
        assert manager.ge("2.2") is True
        assert manager.ge("2.3") is False


def test_ge_without_flink_installed() -> None:  # noqa: D103
    with patch(
        "importlib.metadata.version", side_effect=Exception("Package not found")
    ):
        manager = FlinkVersionManager()
        assert manager.ge("1.20.0") is False


def test_lt_method() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="1.20.3"):
        manager = FlinkVersionManager()
        assert manager.lt("1.21.0") is True
        assert manager.lt("1.20.3") is False
        assert manager.lt("1.20.0") is False


def test_lt_with_two_part_version() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="2.2"):
        manager = FlinkVersionManager()
        assert manager.lt("2.3") is True
        assert manager.lt("2.2") is False
        assert manager.lt("2.0") is False


def test_lt_without_flink_installed() -> None:  # noqa: D103
    with patch(
        "importlib.metadata.version", side_effect=Exception("Package not found")
    ):
        manager = FlinkVersionManager()
        assert manager.lt("2.0.0") is False


def test_lazy_initialization() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="1.20.3") as mock_version:
        manager = FlinkVersionManager()
        # Version should not be fetched yet
        assert not manager._initialized
        mock_version.assert_not_called()

        # First access triggers initialization
        _ = manager.version
        assert manager._initialized
        mock_version.assert_called_once()

        # Second access should use cached value
        _ = manager.version
        mock_version.assert_called_once()  # Still called only once


def test_version_comparison_with_snapshot_versions() -> None:  # noqa: D103
    with patch("importlib.metadata.version", return_value="2.2-SNAPSHOT"):
        manager = FlinkVersionManager()
        assert manager.ge("2.2.0") is True
        assert manager.ge("2.1.0") is True
        assert manager.lt("2.3.0") is True


def test_version_comparison_edge_cases() -> None:  # noqa: D103
    # Test boundary versions
    with patch("importlib.metadata.version", return_value="1.20.3"):
        manager = FlinkVersionManager()
        assert manager.ge("1.20.2") is True
        assert manager.ge("1.20.3") is True
        assert manager.ge("1.20.4") is False
        assert manager.lt("1.20.4") is True
        assert manager.lt("1.20.3") is False
        assert manager.ge("2.0.0") is False
