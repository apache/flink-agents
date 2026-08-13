#!/usr/bin/env bats

setup() {
    load '../helpers/shim'
    shim_setup
    UT_SH="${BATS_TEST_DIRNAME}/../../ut.sh"
    # Every suite below is driven entirely through these two shims, so no
    # Maven reactor or Python environment is touched.
    shim_bin mvn
    shim_bin uv
}

# The warning's own text. Both halves of the claim are pinned — that the Java
# unit tests ignore -f, and which suites it does reach — so re-wording either
# into something false fails here instead of passing silently. Only the
# substantive words are matched, on whitespace-collapsed output, so re-flowing
# or re-punctuating the sentence is not a failure.
WARNING_PREFIX="Warning: -f does not affect the Java unit tests"
WARNING_SCOPE_E2E="applies to the e2e"
WARNING_SCOPE_PYTHON="and Python tests"

flowed_output() {
    printf '%s' "$output" | tr -s '[:space:]' ' '
}

assert_warned() {
    case "$(flowed_output)" in
        *"$WARNING_PREFIX"*"$WARNING_SCOPE_E2E"*"$WARNING_SCOPE_PYTHON"*) ;;
        *) false ;;
    esac
}

assert_not_warned() {
    case "$output" in *"$WARNING_PREFIX"*) false ;; *) ;; esac
}

@test "-f with the Java unit tests warns, on stderr" {
    run bash "$UT_SH" -j -f 1.20
    [ "$status" -eq 0 ]
    assert_warned
    # Dropping stderr must drop the warning with it: a warning on stdout would
    # land in the middle of test output that gets parsed or piped.
    run bash -c "bash '$UT_SH' -j -f 1.20 2>/dev/null"
    [ "$status" -eq 0 ]
    assert_not_warned
}

@test "-f with the e2e tests does not warn" {
    run bash "$UT_SH" -j -e -f 1.20
    [ "$status" -eq 0 ]
    assert_not_warned
}

@test "-f with only the Python tests does not warn" {
    run bash "$UT_SH" -p -f 1.20
    [ "$status" -eq 0 ]
    assert_not_warned
}

@test "-f alongside the default Java+Python selection still warns" {
    run bash "$UT_SH" -f 1.20
    [ "$status" -eq 0 ]
    assert_warned
}

@test "a run that passes no -f does not warn about the defaulted version" {
    run bash "$UT_SH" -j
    [ "$status" -eq 0 ]
    assert_not_warned
}

@test "the warning is printed before any Maven work starts" {
    # The point of warning at all is that it reaches the user before a long
    # build runs, so give it something to be ordered against: a marker the mvn
    # shim writes to stderr. On stderr alone the warning has to come first.
    shim_bin_script mvn 'echo "mvn-shim-ran" >&2'
    run bash -c "bash '$UT_SH' -j -f 1.20 2>&1 1>/dev/null"
    [ "$status" -eq 0 ]
    case "${lines[0]}" in *"$WARNING_PREFIX"*) ;; *) false ;; esac
    # Guard against the ordering holding only because Maven never ran.
    case "$output" in *"mvn-shim-ran"*) ;; *) false ;; esac
}
