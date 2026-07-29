#!/usr/bin/env bats

# validate_venv_dir classifies a candidate VENV_DIR path so the caller
# can either accept it (new / empty / real venv) or re-prompt the user
# (non-empty foreign directory or file).
#
# Echoes one of: new | empty | venv | nonempty | file
# Exit code 0 for new/empty/venv (caller proceeds), 1 for nonempty/file
# (caller re-prompts).

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "validate_venv_dir: path that does not exist → 'new'" {
    local p="$BATS_TEST_TMPDIR/will-be-created"
    run validate_venv_dir "$p"
    [ "$status" -eq 0 ]
    [ "$output" = "new" ]
}

@test "validate_venv_dir: empty directory → 'empty'" {
    local p="$BATS_TEST_TMPDIR/empty"
    mkdir -p "$p"
    run validate_venv_dir "$p"
    [ "$status" -eq 0 ]
    [ "$output" = "empty" ]
}

@test "validate_venv_dir: directory with pyvenv.cfg → 'venv'" {
    local p="$BATS_TEST_TMPDIR/avenv"
    mkdir -p "$p/bin"
    : > "$p/pyvenv.cfg"
    : > "$p/bin/activate"
    run validate_venv_dir "$p"
    [ "$status" -eq 0 ]
    [ "$output" = "venv" ]
}

@test "validate_venv_dir: non-empty foreign directory → 'nonempty' (rc=1)" {
    local p="$BATS_TEST_TMPDIR/foreign"
    mkdir -p "$p"
    : > "$p/some-unrelated-file"
    : > "$p/README.md"
    run validate_venv_dir "$p"
    [ "$status" -eq 1 ]
    [ "$output" = "nonempty" ]
}

@test "validate_venv_dir: path is a regular file → 'file' (rc=1)" {
    local p="$BATS_TEST_TMPDIR/just-a-file"
    : > "$p"
    run validate_venv_dir "$p"
    [ "$status" -eq 1 ]
    [ "$output" = "file" ]
}

@test "validate_venv_dir: directory with only a hidden file is still 'nonempty'" {
    local p="$BATS_TEST_TMPDIR/dot"
    mkdir -p "$p"
    : > "$p/.hiddenfile"
    run validate_venv_dir "$p"
    [ "$status" -eq 1 ]
    [ "$output" = "nonempty" ]
}

@test "validate_venv_dir: bin/activate without pyvenv.cfg is still 'nonempty'" {
    # A user could have a random project with a bin/activate file. Treat
    # only the strict pyvenv.cfg marker as a real venv.
    local p="$BATS_TEST_TMPDIR/halfbaked"
    mkdir -p "$p/bin"
    : > "$p/bin/activate"
    run validate_venv_dir "$p"
    [ "$status" -eq 1 ]
    [ "$output" = "nonempty" ]
}

@test "validate_venv_dir: empty argument → 'file' (rc=1) (treated as invalid)" {
    run validate_venv_dir ""
    [ "$status" -eq 1 ]
}
