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

from packaging import version


def _normalize_version(version_str: str) -> str:
    """Normalize version string to standard format.

    Handles various version formats and normalizes them to a three-part version
    string (major.minor.patch). If only two parts are provided, appends '.0' as
    the patch version.

    Args:
        version_str: The version string to normalize (e.g., "2.2", "1.20.3",
                    "2.2.0-SNAPSHOT", "1.20.dev0", "2.0.rc1")

    Returns:
        str: Normalized version string in format "major.minor.patch"
    """
    # Remove any version suffix with hyphen (e.g., -SNAPSHOT, -dev)
    base_version = version_str.split('-')[0]

    # Split by dot and keep only numeric parts
    parts = []
    for part in base_version.split('.'):
        # Only keep parts that are purely numeric
        if part.isdigit():
            parts.append(part)
        # Stop if we encounter a non-numeric part (e.g., 'dev0', 'rc1')
        else:
            break

    # Ensure we have at least three parts (major.minor.patch)
    while len(parts) < 3:
        parts.append('0')

    return '.'.join(parts[:3])


class FlinkVersionManager:
    """Manager for Apache Flink version compatibility checks.

    This class provides lazy initialization and caching of the installed Flink
    version, along with utility methods for version comparison. It uses a singleton
    pattern through the global flink_version_manager instance.

    The version information is fetched only once when first accessed, improving
    startup performance and avoiding repeated package queries.

    Attributes:
        _flink_version: Cached version string of the installed apache-flink package
        _initialized: Flag indicating whether version has been fetched
    """

    def __init__(self) -> None:
        """Initialize the FlinkVersionManager with uninitialized state."""
        self._flink_version = None
        self._initialized = False

    def _initialize(self) -> None:
        """Perform lazy initialization of the Flink version.

        This method is called automatically when version information is first
        accessed. It fetches the version once and caches it for subsequent calls.
        """
        if self._initialized:
            return

        # Attempt to retrieve the version from installed packages
        self._flink_version = self._get_pyflink_version()
        self._initialized = True

    def _get_pyflink_version(self) -> str | None:
        """Retrieve the version of the installed apache-flink package.

        Uses importlib.metadata to query the package version. This method handles
        cases where the package is not installed or cannot be queried.

        Returns:
            Optional[str]: The version string if apache-flink is installed,
                          None otherwise
        """
        try:
            from importlib.metadata import version as get_version
            return get_version("apache-flink")
        except Exception:
            return None

    @property
    def version(self) -> str | None:
        """Get the full version string of the installed Flink.

        Returns:
            Optional[str]: Full version string (e.g., "1.20.3", "2.2.0") or None
                          if apache-flink is not installed
        """
        self._initialize()
        return self._flink_version

    @property
    def major_version(self) -> str | None:
        """Get the major version number (major.minor) of the installed Flink.

        Extracts the first two version components from the full version string,
        which is useful for feature compatibility checks between major releases.

        Returns:
            Optional[str]: Major version string (e.g., "2.2", "1.20") or None
                          if apache-flink is not installed
        """
        if not self.version:
            return None

        # Extract major.minor from full version string
        # Examples: "2.2.0" -> "2.2", "1.20.3" -> "1.20", "2.2.0-SNAPSHOT" -> "2.2"
        version_parts = self.version.split('-')[0].split('.')
        if len(version_parts) >= 2:
            return f"{version_parts[0]}.{version_parts[1]}"
        return self.version

    def ge(self, target_version: str) -> bool:
        """Check if the installed Flink version is greater than or equal to the target.

        Args:
            target_version: The minimum version to compare against (e.g., "1.20.0")

        Returns:
            bool: True if installed version >= target version, False otherwise
                  (including when Flink is not installed)
        """
        if not self.version:
            return False

        current = _normalize_version(self.version)
        target = _normalize_version(target_version)
        return version.parse(current) >= version.parse(target)

    def lt(self, target_version: str) -> bool:
        """Check if the installed Flink version is less than the target.

        Args:
            target_version: The version threshold to compare against (e.g., "2.0.0")

        Returns:
            bool: True if installed version < target version, False otherwise
                  (including when Flink is not installed)
        """
        if not self.version:
            return False
        return not self.ge(target_version)


# Global singleton instance for Flink version management
flink_version_manager = FlinkVersionManager()
