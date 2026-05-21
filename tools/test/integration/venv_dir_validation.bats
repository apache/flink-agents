#!/usr/bin/env bats

# End-to-end behavior of VENV_DIR validation. In non-interactive mode
# (the only mode the integration runner can drive), passing a foreign
# non-empty directory as VENV_DIR must abort BEFORE downloading Flink.

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    shim_setup
    # Fake an existing Flink so plan_flink (INSTALL_FLINK=No) passes.
    export FLINK_HOME="$BATS_TEST_TMPDIR/flink-home"
    mkdir -p "$FLINK_HOME/lib"
    : > "$FLINK_HOME/lib/flink-dist-2.2.0.jar"
    # Fake java so check_java passes.
    shim_bin_script java "
case \"\$1\" in
    -version) echo 'openjdk version \"17.0.2\"' >&2 ;;
esac
"
}

@test "VENV_DIR=non-empty foreign dir + --non-interactive → die before download" {
    local foreign="$BATS_TEST_TMPDIR/foreign"
    mkdir -p "$foreign"
    : > "$foreign/unrelated.txt"

    run env VENV_DIR="$foreign" PYTHON_BIN=/no/such/python3 \
        bash "${BATS_TEST_DIRNAME}/../../install.sh" \
            --non-interactive --enable-pyflink

    [ "$status" -ne 0 ]
    case "$output" in
        *"already exists and is not a Python venv"*) ;;
        *) false ;;
    esac
    # Critical: must NOT have invoked any downloader before failing.
    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count wget)" = "0" ]
}

@test "VENV_DIR=existing real venv (has pyvenv.cfg) is accepted in non-interactive mode" {
    local venv="$BATS_TEST_TMPDIR/real-venv"
    mkdir -p "$venv/bin"
    : > "$venv/pyvenv.cfg"
    : > "$venv/bin/activate"

    # We don't actually want it to *install* — just to get past the
    # plan_pyflink validation. Use --dry-run so plan succeeds and we
    # bail before stage 3.
    run env VENV_DIR="$venv" PYTHON_BIN=/no/such/python3 \
        bash "${BATS_TEST_DIRNAME}/../../install.sh" \
            --non-interactive --enable-pyflink --dry-run
    # Either the dry-run printout completes (status 0) or fails for an
    # unrelated reason (e.g. PYTHON_BIN missing in non-interactive resolve_python).
    # The thing we explicitly want NOT to see: the VENV_DIR rejection.
    case "$output" in
        *"is not a Python venv"*) false ;;
        *) ;;
    esac
}
