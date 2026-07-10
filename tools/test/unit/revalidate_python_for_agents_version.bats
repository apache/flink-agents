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
    local name="$1"
    local ver="$2"
    shim_bin_script "$name" "
case \"\$*\" in
    *'sys.version_info.major'*)
        echo '$ver'
        ;;
esac
"
}

# Scenario from review: interpreter resolved under 0.3.0 (ceiling 3.13),
# then the version is edited down to 0.2.1 (ceiling 3.12) at the confirm
# screen. The stale interpreter must be dropped and re-resolved.
@test "revalidate_python_for_agents_version: re-resolves when the edited version drops the ceiling below PYTHON_BIN" {
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    revalidate_python_for_agents_version
    [ "$PYTHON_BIN" = "python3" ]
}

@test "revalidate_python_for_agents_version: keeps an interpreter that still satisfies the new version" {
    fake_python fake_py312 "3.12"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.3.0"

    revalidate_python_for_agents_version
    [ "$PYTHON_BIN" = "fake_py312" ]
}

@test "revalidate_python_for_agents_version: no-op when PyFlink is disabled" {
    fake_python fake_py312 "3.12"

    ENABLE_PYFLINK="No"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    revalidate_python_for_agents_version
    [ "$PYTHON_BIN" = "fake_py312" ]
}

@test "revalidate_python_for_agents_version: no-op when no interpreter was resolved yet" {
    ENABLE_PYFLINK="Yes"
    PYTHON_BIN=""
    FLINK_AGENTS_VERSION="0.2.1"

    run revalidate_python_for_agents_version
    [ "$status" -eq 0 ]
}

@test "revalidate_python_for_agents_version: warns before re-resolving a stale interpreter" {
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    run revalidate_python_for_agents_version
    [ "$status" -eq 0 ]
    [[ "$output" == *"incompatible with Flink Agents 0.2.1"* ]]
    [[ "$output" == *"<3.12"* ]]
}
