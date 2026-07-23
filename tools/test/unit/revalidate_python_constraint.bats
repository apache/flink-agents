#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
    # Default the venv to an absent path so revalidate_existing_venv is a
    # no-op unless a test builds a real venv on purpose.
    VENV_DIR="$BATS_TEST_TMPDIR/no-venv"
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

# Helper: build a fake existing venv whose interpreter reports $ver.
fake_venv() {
    local dir="$1"
    local ver="$2"
    mkdir -p "$dir/bin"
    : > "$dir/pyvenv.cfg"
    : > "$dir/bin/activate"
    cat > "$dir/bin/python" <<EOF
#!/usr/bin/env bash
case "\$*" in
    *'sys.version_info.major'*) echo '$ver' ;;
esac
EOF
    chmod +x "$dir/bin/python"
}

# --- PYTHON_BIN re-resolution (Flink Agents axis) ---

# Scenario from review: interpreter resolved under 0.3.0 (ceiling 3.13),
# then the version is edited down to 0.2.1 (ceiling 3.12) at the confirm
# screen. The stale interpreter must be dropped and re-resolved.
@test "revalidate_python_constraint: re-resolves when the edited Agents version drops the ceiling below PYTHON_BIN" {
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    revalidate_python_constraint
    [ "$PYTHON_BIN" = "python3" ]
}

@test "revalidate_python_constraint: keeps an interpreter that still satisfies the new version" {
    fake_python fake_py312 "3.12"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.3.0"

    revalidate_python_constraint
    [ "$PYTHON_BIN" = "fake_py312" ]
}

@test "revalidate_python_constraint: no-op when PyFlink is disabled" {
    fake_python fake_py312 "3.12"

    ENABLE_PYFLINK="No"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    revalidate_python_constraint
    [ "$PYTHON_BIN" = "fake_py312" ]
}

@test "revalidate_python_constraint: no-op when no interpreter was resolved yet" {
    ENABLE_PYFLINK="Yes"
    PYTHON_BIN=""
    FLINK_AGENTS_VERSION="0.2.1"

    run revalidate_python_constraint
    [ "$status" -eq 0 ]
}

@test "revalidate_python_constraint: warns before re-resolving a stale interpreter" {
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.2.1"

    run revalidate_python_constraint
    [ "$status" -eq 0 ]
    [[ "$output" == *"incompatible with Flink Agents 0.2.1"* ]]
    [[ "$output" == *"<3.12"* ]]
}

# --- PYTHON_BIN re-resolution (Flink axis) ---

# Python 3.12 needs Flink 2.1+. Editing the Flink version down to 2.0.x while
# Agents stays 0.3.0 must still drop a 3.12 interpreter.
@test "revalidate_python_constraint: re-resolves when the edited Flink version drops the ceiling below PYTHON_BIN" {
    fake_python fake_py312 "3.12"
    fake_python python3 "3.11"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.3.0"
    FLINK_VERSION="2.0.2"

    revalidate_python_constraint
    [ "$PYTHON_BIN" = "python3" ]
}

@test "revalidate_python_constraint: keeps a 3.12 interpreter when Flink is 2.1+" {
    fake_python fake_py312 "3.12"

    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="fake_py312"
    FLINK_AGENTS_VERSION="0.3.0"
    FLINK_VERSION="2.1.3"

    revalidate_python_constraint
    [ "$PYTHON_BIN" = "fake_py312" ]
}

# --- existing-venv revalidation ---

# The reviewer's second scenario: PYTHON_BIN re-resolves fine (3.11 is valid
# for both versions), but an existing venv built on 3.12 would still be reused
# by setup_python_env. Non-interactive callers must get an actionable error.
@test "revalidate_existing_venv: non-interactive die when the existing venv interpreter is too new" {
    fake_python python3 "3.11"
    fake_venv "$BATS_TEST_TMPDIR/venv312" "3.12"

    NO_PROMPT=1
    ENABLE_PYFLINK="Yes"
    PYTHON_BIN="python3"
    FLINK_AGENTS_VERSION="0.2.1"
    VENV_DIR="$BATS_TEST_TMPDIR/venv312"

    run revalidate_existing_venv
    [ "$status" -ne 0 ]
    [[ "$output" == *"uses Python 3.12"* ]]
    [[ "$output" == *"different path"* ]]
}

@test "revalidate_existing_venv: no-op when the existing venv interpreter is compatible" {
    fake_venv "$BATS_TEST_TMPDIR/venv311" "3.11"

    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.2.1"
    VENV_DIR="$BATS_TEST_TMPDIR/venv311"
    RECREATE_VENV_PATH=""

    run revalidate_existing_venv
    [ "$status" -eq 0 ]
    [ "$RECREATE_VENV_PATH" = "" ]
}

@test "revalidate_existing_venv: no-op when VENV_DIR is not an existing venv" {
    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.2.1"
    VENV_DIR="$BATS_TEST_TMPDIR/does-not-exist"

    run revalidate_existing_venv
    [ "$status" -eq 0 ]
}

@test "revalidate_existing_venv: binds recreation approval to the current venv path" {
    local old_venv="$BATS_TEST_TMPDIR/venv312"
    fake_venv "$old_venv" "3.12"

    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.2.1"
    VENV_DIR="$old_venv"
    is_promptable() { return 0; }
    choose_install_method_interactive() { return 0; }

    revalidate_existing_venv
    [ "$RECREATE_VENV_PATH" = "$old_venv" ]
}

@test "revalidate_existing_venv: clears recreation approval after the venv path changes" {
    local old_venv="$BATS_TEST_TMPDIR/venv312"
    local new_venv="$BATS_TEST_TMPDIR/venv311"
    fake_venv "$old_venv" "3.12"
    fake_venv "$new_venv" "3.11"

    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.2.1"
    VENV_DIR="$new_venv"
    RECREATE_VENV_PATH="$old_venv"

    revalidate_existing_venv
    [ "$RECREATE_VENV_PATH" = "" ]
}

@test "setup_python_env: does not apply recreation approval to a different venv" {
    local approved_venv="$BATS_TEST_TMPDIR/approved-venv"
    local selected_venv="$BATS_TEST_TMPDIR/selected-venv"
    fake_venv "$approved_venv" "3.12"
    fake_venv "$selected_venv" "3.11"
    touch "$selected_venv/must-survive"

    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.3.0"
    FLINK_VERSION="2.0.2"
    FLINK_MAJOR_MINOR="2.0"
    VENV_DIR="$selected_venv"
    RECREATE_VENV_PATH="$approved_venv"
    create_venv() { fake_venv "$VENV_DIR" "3.11"; }
    pip_install_quiet() { return 0; }

    run setup_python_env
    [ "$status" -eq 0 ]
    [ -f "$selected_venv/must-survive" ]
}

@test "setup_python_env: recreates the exact venv path that was approved" {
    local selected_venv="$BATS_TEST_TMPDIR/selected-venv"
    fake_venv "$selected_venv" "3.12"
    touch "$selected_venv/old-marker"

    ENABLE_PYFLINK="Yes"
    FLINK_AGENTS_VERSION="0.2.1"
    FLINK_VERSION="2.0.2"
    FLINK_MAJOR_MINOR="2.0"
    VENV_DIR="$selected_venv"
    RECREATE_VENV_PATH="$selected_venv"
    create_venv() { fake_venv "$VENV_DIR" "3.11"; }
    pip_install_quiet() { return 0; }

    run setup_python_env
    [ "$status" -eq 0 ]
    [ ! -f "$selected_venv/old-marker" ]
}
