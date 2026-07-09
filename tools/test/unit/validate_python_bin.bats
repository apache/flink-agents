#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
}

# Helper: write a fake python that reports a chosen version.
fake_python() {
    local ver="$1"
    shim_bin_script fake_py "
case \"\$*\" in
    *'sys.version_info.major'*)
        echo '$ver'
        ;;
esac
"
}

@test "validate_python_bin: empty path is rejected" {
    run validate_python_bin ""
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: missing binary is rejected" {
    run validate_python_bin /no/such/bin
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: Python 3.10 is accepted" {
    fake_python "3.10"
    run validate_python_bin fake_py
    [ "$status" -eq 0 ]
}

@test "validate_python_bin: Python 3.11 is accepted" {
    fake_python "3.11"
    run validate_python_bin fake_py
    [ "$status" -eq 0 ]
}

@test "validate_python_bin: Python 3.9 is rejected" {
    fake_python "3.9"
    run validate_python_bin fake_py
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: Python 3.12 is rejected for Flink Agents 0.2.x" {
    FLINK_AGENTS_VERSION="0.2.1"
    fake_python "3.12"
    run validate_python_bin fake_py
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: Python 3.12 is accepted for Flink Agents 0.3.0+" {
    FLINK_AGENTS_VERSION="0.3.0"
    fake_python "3.12"
    run validate_python_bin fake_py
    [ "$status" -eq 0 ]
}

@test "validate_python_bin: Python 3.13 is rejected for Flink Agents 0.3.0+" {
    FLINK_AGENTS_VERSION="0.3.0"
    fake_python "3.13"
    run validate_python_bin fake_py
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: Python 2.7 is rejected" {
    fake_python "2.7"
    run validate_python_bin fake_py
    [ "$status" -ne 0 ]
}

@test "validate_python_bin: binary that prints nothing is rejected" {
    shim_bin_script fake_py 'exit 0'
    run validate_python_bin fake_py
    [ "$status" -ne 0 ]
}
