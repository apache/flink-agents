#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "parse_args: --non-interactive sets NO_PROMPT=1" {
    parse_args --non-interactive
    [ "$NO_PROMPT" = "1" ]
}

@test "parse_args: --install-flink sets INSTALL_FLINK=Yes" {
    parse_args --install-flink
    [ "$INSTALL_FLINK" = "Yes" ]
}

@test "parse_args: --enable-pyflink sets ENABLE_PYFLINK=Yes" {
    parse_args --enable-pyflink
    [ "$ENABLE_PYFLINK" = "Yes" ]
}

@test "parse_args: --enable-pyFlink alias is honored" {
    parse_args --enable-pyFlink
    [ "$ENABLE_PYFLINK" = "Yes" ]
}

@test "parse_args: --verbose sets VERBOSE=1" {
    parse_args --verbose
    [ "$VERBOSE" = "1" ]
}

@test "parse_args: --dry-run sets DRY_RUN=1" {
    parse_args --dry-run
    [ "$DRY_RUN" = "1" ]
}

@test "parse_args: --python <path> sets PYTHON_BIN" {
    parse_args --python /opt/py/bin/python3
    [ "$PYTHON_BIN" = "/opt/py/bin/python3" ]
}

@test "parse_args: --python=<path> sets PYTHON_BIN" {
    parse_args --python=/usr/local/bin/python3.11
    [ "$PYTHON_BIN" = "/usr/local/bin/python3.11" ]
}

@test "parse_args: --python without arg dies" {
    run parse_args --python
    [ "$status" -ne 0 ]
    [[ "$output" == *"--python requires a path argument"* ]]
}

@test "parse_args: --help sets HELP=1" {
    parse_args --help
    [ "$HELP" = "1" ]
}

@test "parse_args: -h sets HELP=1" {
    parse_args -h
    [ "$HELP" = "1" ]
}

@test "parse_args: unknown flag warns but does not die" {
    run parse_args --no-such-flag
    [ "$status" -eq 0 ]
    [[ "$output" == *"Unknown option: --no-such-flag"* ]]
}

@test "parse_args: combined flags all apply" {
    parse_args --non-interactive --install-flink --enable-pyflink
    [ "$NO_PROMPT" = "1" ]
    [ "$INSTALL_FLINK" = "Yes" ]
    [ "$ENABLE_PYFLINK" = "Yes" ]
}
