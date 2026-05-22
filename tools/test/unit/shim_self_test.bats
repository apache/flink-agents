#!/usr/bin/env bats

bats_require_minimum_version 1.5.0

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
}

@test "shim_bin records argv for a single call" {
    shim_bin fake_tool
    fake_tool --foo bar baz
    [ "$(shim_call_count fake_tool)" = "1" ]
    run shim_calls fake_tool
    [ "$output" = "--foo	bar	baz" ]
}

@test "shim_bin can simulate non-zero exit" {
    shim_bin fake_tool 7
    run fake_tool
    [ "$status" -eq 7 ]
}

@test "shim_bin_script can write output files" {
    shim_bin_script fake_tar 'touch "$2/marker"'
    mkdir -p "$BATS_TEST_TMPDIR/out"
    fake_tar -x "$BATS_TEST_TMPDIR/out"
    [ -f "$BATS_TEST_TMPDIR/out/marker" ]
}

@test "PATH shim is preferred over real binary" {
    shim_bin curl
    run command -v curl
    [[ "$output" == "$BATS_TEST_TMPDIR/bin/curl" ]]
}

@test "shim_bin_missing makes command -v report missing" {
    shim_bin_missing tool_x
    run command -v tool_x
    [ "$status" -ne 0 ]
}

@test "shim_bin_missing makes direct invocation exit 127" {
    shim_bin_missing tool_y
    run -127 tool_y --some arg
    [ "$status" -eq 127 ]
}
