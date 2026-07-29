#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

# edit_plan_quote single-quotes its argument so the parent shell can
# safely `source` the dumped state file. Round-tripping the output via
# eval should reproduce the original string exactly.

@test "edit_plan_quote: empty string round-trips" {
    local quoted
    quoted="$(edit_plan_quote "")"
    local out
    eval "out=$quoted"
    [ "$out" = "" ]
}

@test "edit_plan_quote: plain path round-trips" {
    local quoted
    quoted="$(edit_plan_quote "/opt/flink")"
    local out
    eval "out=$quoted"
    [ "$out" = "/opt/flink" ]
}

@test "edit_plan_quote: path with spaces round-trips" {
    local quoted
    quoted="$(edit_plan_quote "/home/jin doe/flink")"
    local out
    eval "out=$quoted"
    [ "$out" = "/home/jin doe/flink" ]
}

@test "edit_plan_quote: path with single quote round-trips" {
    local input="/tmp/it's-fine"
    local quoted
    quoted="$(edit_plan_quote "$input")"
    local out
    eval "out=$quoted"
    [ "$out" = "$input" ]
}

@test "edit_plan_quote: shell metacharacters are not expanded on source-back" {
    local input='/tmp/$HOME-or-$(rm -rf /)'
    local quoted
    quoted="$(edit_plan_quote "$input")"
    local out
    eval "out=$quoted"
    [ "$out" = "$input" ]
}

@test "edit_plan_dump_state: writes a sourceable file that restores values" {
    INSTALL_FLINK="No"
    FLINK_VERSION="2.1.1"
    INSTALL_DIR="/tmp/old"
    FLINK_HOME="/usr/local/flink"
    FLINK_MAJOR_MINOR="2.1"
    ENABLE_PYFLINK="Yes"
    PYFLINK_ACTUALLY_ENABLED=1
    VENV_DIR="/tmp/venv with space"
    PYTHON_BIN="/usr/bin/python3"
    FLINK_AGENTS_VERSION="0.2.0"
    RECREATE_VENV_PATH="/tmp/venv with space"

    local f="$BATS_TEST_TMPDIR/state"
    edit_plan_dump_state "$f"

    # Clobber the live values, then re-source.
    INSTALL_FLINK=""; FLINK_VERSION=""; INSTALL_DIR=""; FLINK_HOME=""
    FLINK_MAJOR_MINOR=""; ENABLE_PYFLINK=""; PYFLINK_ACTUALLY_ENABLED=0
    VENV_DIR=""; PYTHON_BIN=""; FLINK_AGENTS_VERSION=""
    RECREATE_VENV_PATH=""

    # shellcheck disable=SC1090
    source "$f"

    [ "$INSTALL_FLINK" = "No" ]
    [ "$FLINK_VERSION" = "2.1.1" ]
    [ "$INSTALL_DIR" = "/tmp/old" ]
    [ "$FLINK_HOME" = "/usr/local/flink" ]
    [ "$FLINK_MAJOR_MINOR" = "2.1" ]
    [ "$ENABLE_PYFLINK" = "Yes" ]
    [ "$PYFLINK_ACTUALLY_ENABLED" = "1" ]
    [ "$VENV_DIR" = "/tmp/venv with space" ]
    [ "$PYTHON_BIN" = "/usr/bin/python3" ]
    [ "$FLINK_AGENTS_VERSION" = "0.2.0" ]
    [ "$RECREATE_VENV_PATH" = "/tmp/venv with space" ]
}
