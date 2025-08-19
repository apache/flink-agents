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
import tempfile
from pathlib import Path

import pytest
import yaml

from flink_agents.api.configuration import ConfigOption
from flink_agents.plan.configuration import AgentConfiguration


def test_load_configuration_from_file() -> None:
    """Test loading configuration from a YAML file."""
    # Create a temporary YAML file with test data
    test_data = {
        "agent": {
            "database": {
                "host": "localhost",
                "port": 5432,
                "credentials": {"username": "admin", "password": "secret"},
            },
            "api": {"endpoint": "/api/v1", "timeout": 30.0},
            "debug": True,
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
        yaml.dump(test_data, f)
        config_file = f.name

    try:
        # Load configuration
        config = AgentConfiguration()
        config.load_from_file(config_file)

        # Test that nested configuration is properly flattened
        assert config.get_str('database.host') == 'localhost'
        assert config.get_int('database.port') == 5432
        assert config.get_str('database.credentials.username') == 'admin'
        assert config.get_str('database.credentials.password') == 'secret'
        assert config.get_str('api.endpoint') == '/api/v1'
        assert config.get_float('api.timeout') == 30.0
        assert config.get_bool('debug') is True
    finally:
        config_file = Path(config_file)
        config_file.unlink()


def test_load_configuration_without_path() -> None:
    """Test loading configuration without a path (should not change _conf_data)."""
    config = AgentConfiguration()
    # Initially _conf_data should be empty or unchanged
    original_conf_data = config.get_conf_data().copy()
    config.load_from_file(None)
    # _conf_data should remain unchanged
    assert config.get_conf_data() == original_conf_data


def test_load_configuration_with_invalid_file() -> None:
    """Test loading configuration with a non-existent file."""
    config = AgentConfiguration()
    with pytest.raises(FileNotFoundError):
        config.load_from_file('/path/to/nonexistent/file.yaml')


def test_load_configuration_with_invalid_yaml() -> None:
    """Test loading configuration with invalid YAML content."""
    with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
        f.write('invalid: yaml: content: [')
        config_file = f.name

    try:
        config = AgentConfiguration()
        with pytest.raises(yaml.YAMLError):
            config.load_from_file(config_file)
    finally:
        config_file = Path(config_file)
        config_file.unlink()


def test_get_int() -> None:
    """Test get_int method with various inputs."""
    config = AgentConfiguration({'int_key': 42, 'str_key': '123', 'invalid_key': 'not_an_int'})

    # Test normal integer value
    assert config.get_int('int_key') == 42

    # Test string that can be converted to int
    assert config.get_int('str_key') == 123

    # Test default value when key is not found
    assert config.get_int('missing_key', 999) == 999

    # Test default value when no default specified
    assert config.get_int('missing_key') is None

    # Test invalid value that cannot be converted to int
    with pytest.raises(ValueError, match="Invalid value for invalid_key: not_an_int"):
        config.get_int('invalid_key')


def test_get_float() -> None:
    """Test get_float method with various inputs."""
    config = AgentConfiguration({'float_key': 3.14, 'int_key': 42, 'str_key': '2.5', 'invalid_key': 'not_a_float'})

    # Test normal float value
    assert config.get_float('float_key') == 3.14

    # Test int value converted to float
    assert config.get_float('int_key') == 42.0

    # Test string that can be converted to float
    assert config.get_float('str_key') == 2.5

    # Test default value when key is not found
    assert config.get_float('missing_key', 1.23) == 1.23

    # Test default value when no default specified
    assert config.get_float('missing_key') is None

    # Test invalid value that cannot be converted to float
    with pytest.raises(ValueError, match="Invalid value for invalid_key: not_a_float"):
        config.get_float('invalid_key')


def test_get_bool() -> None:
    """Test get_bool method with various inputs."""
    config = AgentConfiguration({'bool_key': True, 'false_key': False, 'str_key': 'true'})

    # Test normal boolean values
    assert config.get_bool('bool_key') is True
    assert config.get_bool('false_key') is False

    # Test default value when key is not found
    assert config.get_bool('missing_key', True) is True

    # Test default value when no default specified
    assert config.get_bool('missing_key') is None

    # Note: bool() in Python behaves differently than might be expected
    # bool('true') is True, but that's Python behavior, not a bug in our code
    assert config.get_bool('str_key') is True


def test_get_str() -> None:
    """Test get_str method with various inputs."""
    config = AgentConfiguration({'str_key': 'hello', 'int_key': 42, 'float_key': 3.14})

    # Test normal string value
    assert config.get_str('str_key') == 'hello'

    # Test int value converted to string
    assert config.get_str('int_key') == '42'

    # Test float value converted to string
    assert config.get_str('float_key') == '3.14'

    # Test default value when key is not found
    assert config.get_str('missing_key', 'default') == 'default'

    # Test default value when no default specified
    assert config.get_str('missing_key') is None

    # Test None value
    assert config.get_str('none_key') is None

def test_get_with_config_option() -> None:  # noqa: D103
    data = {
        "config.str": "config.value",
        "config.int": 6789,
        "config.float": "45.5",
        "config.boolean": True,
    }

    config = AgentConfiguration(data)

    str_option = ConfigOption("config.str", str, "default_str")
    int_option = ConfigOption("config.int", int, 123)
    float_option = ConfigOption("config.float", float, 0.0)
    bool_option = ConfigOption("config.boolean", bool, False)

    assert config.get(str_option) == "config.value"
    assert config.get(int_option) == 6789
    assert config.get(float_option) == 45.5
    assert config.get(bool_option) is True

    missing_option = ConfigOption("missing.key1", int, 22)
    assert config.get(missing_option) == 22

    missing_key = ConfigOption("missing.key2", int, None)
    assert config.get(missing_key) is None


def test_get_with_default_value() -> None:  # noqa: D103
    default_str = ConfigOption("default.str", str, "default_value")
    default_int = ConfigOption("default.int", int, 100)
    default_double = ConfigOption("default.double", float, 2.5)

    config = AgentConfiguration()

    assert config.get(default_str) == "default_value"
    assert config.get(default_int) == 100
    assert config.get(default_double) == 2.5


def test_get_with_null_and_default() -> None:  # noqa: D103
    nullable_str = ConfigOption("nullable.str", str, "default")

    config = AgentConfiguration()
    config.set_str("nullable.str", None)

    assert config.get(nullable_str) == "default"
