#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "normalize_path: empty input yields empty string" {
    run normalize_path ""
    [ "$status" -eq 0 ]
    [ "$output" = "" ]
}

@test "normalize_path: tilde expands to \$HOME" {
    HOME="/home/u" run normalize_path "~/foo"
    [ "$status" -eq 0 ]
    [ "$output" = "/home/u/foo" ]
}

@test "normalize_path: relative path resolves against PWD" {
    cd "$BATS_TEST_TMPDIR"
    run normalize_path "bar"
    [ "$status" -eq 0 ]
    [ "$output" = "$BATS_TEST_TMPDIR/bar" ]
}

@test "normalize_path: dot relative is collapsed to current dir" {
    cd "$BATS_TEST_TMPDIR"
    run normalize_path "."
    [ "$status" -eq 0 ]
    [ "$output" = "$BATS_TEST_TMPDIR" ]
}

@test "normalize_path: trailing slash is stripped" {
    run normalize_path "/x/y/"
    [ "$status" -eq 0 ]
    [ "$output" = "/x/y" ]
}

@test "normalize_path: multiple trailing slashes are stripped" {
    run normalize_path "/x/y///"
    [ "$status" -eq 0 ]
    [ "$output" = "/x/y" ]
}

@test "normalize_path: consecutive slashes are collapsed" {
    run normalize_path "/x//y///z"
    [ "$status" -eq 0 ]
    [ "$output" = "/x/y/z" ]
}

@test "normalize_path: root '/' is preserved" {
    run normalize_path "/"
    [ "$status" -eq 0 ]
    [ "$output" = "/" ]
}

@test "normalize_path: combined tilde + dot + trailing slash" {
    HOME=/h
    run normalize_path "~/a/./b/"
    [ "$status" -eq 0 ]
    [ "$output" = "/h/a/b" ]
}
