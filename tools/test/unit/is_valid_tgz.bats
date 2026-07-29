#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
    WORK="$BATS_TEST_TMPDIR/tgz"
    mkdir -p "$WORK"
}

@test "is_valid_tgz: missing file is invalid" {
    run is_valid_tgz "$WORK/nope.tgz"
    [ "$status" -ne 0 ]
}

@test "is_valid_tgz: empty file is invalid" {
    printf '\x00' > "$WORK/empty.tgz"
    run is_valid_tgz "$WORK/empty.tgz"
    [ "$status" -ne 0 ]
}

@test "is_valid_tgz: random bytes are invalid" {
    printf 'not a tarball at all\n' > "$WORK/junk.tgz"
    run is_valid_tgz "$WORK/junk.tgz"
    [ "$status" -ne 0 ]
}

@test "is_valid_tgz: a real tgz is valid" {
    mkdir -p "$WORK/src"
    echo hello > "$WORK/src/file.txt"
    tar -czf "$WORK/good.tgz" -C "$WORK/src" .
    run is_valid_tgz "$WORK/good.tgz"
    [ "$status" -eq 0 ]
}

@test "is_valid_tgz: truncated tgz is invalid" {
    mkdir -p "$WORK/src"
    head -c 4096 /dev/urandom > "$WORK/src/blob"
    tar -czf "$WORK/full.tgz" -C "$WORK/src" .
    # truncate to first 32 bytes (gzip header only)
    head -c 32 "$WORK/full.tgz" > "$WORK/trunc.tgz"
    run is_valid_tgz "$WORK/trunc.tgz"
    [ "$status" -ne 0 ]
}
