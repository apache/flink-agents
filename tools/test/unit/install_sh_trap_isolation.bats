#!/usr/bin/env bats

# install.sh installs its EXIT, INT and ERR traps at top level, so a `source`
# installs them too. Every file whose setup() calls load_install_sh sources it,
# and bats prints a failing or skipped result from inside its own EXIT trap: a
# handler laid over that one makes such a result vanish from the stream
# altogether, and an ERR handler rewrites the trace of whatever survives.
# Sourcing install.sh with FLINK_AGENTS_INSTALL_SH_NO_RUN=1 therefore has to
# leave the caller's handlers as it found them.
#
# A test cannot observe its own missing result, so both tests below drive a
# nested bats run over a throwaway file and read that run's TAP stream.

setup() {
    # Deliberately no `load '../helpers/load'` here: sourcing install.sh into
    # this file is the condition under test, and would suppress its own results.
    INSTALL_SH="${BATS_TEST_DIRNAME}/../../install.sh"
}

# Writes a bats file that sources install.sh the way load_install_sh does and
# then fails and skips, and prints its path. It is written at run time rather
# than committed because run.sh collects unit/ and integration/ recursively,
# and a committed copy would be run as a real test file.
#
# It cannot `load '../helpers/load'`: bats resolves a helper path against the
# directory of the file doing the loading, which here is the temp directory. The
# path to install.sh arrives through the environment instead.
write_sourcing_fixture() {
    local fixture="$BATS_TEST_TMPDIR/sourcing_fixture.bats"
    cat > "$fixture" <<'EOF'
#!/usr/bin/env bats

setup() {
    export FLINK_AGENTS_INSTALL_SH_NO_RUN=1
    # shellcheck disable=SC1090
    source "$FIXTURE_INSTALL_SH"
}

@test "fixture: a failing assertion" {
    [[ "expected" == "actual" ]] || false
}

@test "fixture: a skip" {
    skip "the reason reaches the stream"
}
EOF
    printf '%s' "$fixture"
}

# Fails the calling test after printing the nested run's TAP stream, which every
# assertion below inspects and which is otherwise unrecoverable: bats removes
# $BATS_TEST_TMPDIR with the rest of the run's temporary tree once the run ends,
# taking the fixture and its output with it. A test body's stderr reaches the
# report as comment lines.
fail_with_tap() {
    printf 'nested TAP stream:\n%s\n' "$output" >&2
    false
}

@test "sourcing install.sh: a failing assertion is reported, and blamed on the test" {
    local fixture
    fixture="$(write_sourcing_fixture)"
    # The line the trace has to name, read back from the fixture so it stays
    # correct when the fixture above is edited.
    local fail_line
    fail_line="$(grep -n '"expected" == "actual"' "$fixture" | cut -d: -f1)"

    run env FIXTURE_INSTALL_SH="$INSTALL_SH" \
        "$BATS_ROOT/bin/bats" --formatter tap "$fixture"

    [[ "$output" == *"not ok 1 fixture: a failing assertion"* ]] || fail_with_tap
    # The opening of the trace, not just the file it names. A trace bats
    # produced itself opens with `# (in test file `; install.sh's ERR handler
    # prepends its own `from function 'on_error'` frame, displacing that
    # opening.
    [[ "$output" == *"# (in test file $fixture, line $fail_line)"* ]] || fail_with_tap
    # A trace led by install.sh's handler, and the banner it prints, both blame
    # the installer for a failure that belongs to the test.
    [[ "$output" != *"on_error"* ]] || fail_with_tap
    [[ "$output" != *"Installation failed"* ]] || fail_with_tap
}

@test "sourcing install.sh: a skip is reported" {
    local fixture
    fixture="$(write_sourcing_fixture)"

    run env FIXTURE_INSTALL_SH="$INSTALL_SH" \
        "$BATS_ROOT/bin/bats" --formatter tap "$fixture"

    [[ "$output" == *"ok 2 fixture: a skip # skip the reason reaches the stream"* ]] || fail_with_tap
}
