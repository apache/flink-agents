#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
    VENV_DIR="$BATS_TEST_TMPDIR/no-venv"
}

fake_python() {
    local name="$1"
    local ver="$2"
    shim_bin_script "$name" "
case \"\$*\" in
    *'sys.version_info.major'*) echo '$ver' ;;
esac
"
}

@test "reconcile_plan_after_edit: re-detects FLINK_HOME and revalidates Python" {
    local flink_home="$BATS_TEST_TMPDIR/flink-2.0.2"
    mkdir -p "$flink_home/lib"
    : > "$flink_home/lib/flink-dist-2.0.2.jar"
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    INSTALL_FLINK="No"
    FLINK_HOME="$flink_home"
    FLINK_VERSION="2.2.1"
    FLINK_MAJOR_MINOR="2.2"
    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.3.0"
    PYTHON_BIN="fake_py312"

    reconcile_plan_after_edit flink_home

    [ "$FLINK_VERSION" = "2.0.2" ]
    [ "$FLINK_MAJOR_MINOR" = "2.0" ]
    [ "$PYTHON_BIN" = "python3" ]
}

@test "reconcile_plan_after_edit: derives FLINK_HOME for managed installs" {
    INSTALL_FLINK="Yes"
    INSTALL_DIR="$BATS_TEST_TMPDIR/flink installs"
    FLINK_VERSION="2.1.3"
    FLINK_HOME="/stale/home"
    FLINK_MAJOR_MINOR="2.2"

    reconcile_plan_after_edit install_dir

    [ "$FLINK_HOME" = "$INSTALL_DIR/flink-2.1.3" ]
    [ "$FLINK_MAJOR_MINOR" = "2.1" ]
}
