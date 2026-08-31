#!/usr/bin/env bats

setup() {
    load '../helpers/shim'
    load '../helpers/fake_root'
    shim_setup
    UT_SH="${BATS_TEST_DIRNAME}/../../ut.sh"
    REPO_POM="${BATS_TEST_DIRNAME}/../../../pom.xml"
    # Every suite below is driven entirely through these two shims, so no
    # Maven reactor or Python environment is touched.
    shim_bin mvn
    shim_bin uv
}

# Two-component Flink version the root pom pins, e.g. "2.3" for <flink.version>2.3.0.
repo_flink_minor() {
    local v
    v="$(sed -n 's/.*<flink.version>\(.*\)<\/flink.version>.*/\1/p' "$REPO_POM" | head -1)"
    echo "${v%.*}"
}

# The rejection's own text. Every substantive claim is pinned separately -- that
# -e is what makes -f usable, which suite it selects for, that the unit tests
# cannot be pointed elsewhere at all, and what each half of them builds against
# instead -- so re-wording any one of them into something false fails here
# rather than passing silently. Only those words are matched, on
# whitespace-collapsed output, so re-flowing or re-punctuating the message is
# not a failure.
ERROR_REQUIRES_E2E="Error: -f requires -e"
ERROR_E2E_SCOPE="it selects the Flink version the e2e tests run against"
ERROR_NO_RETARGET="The unit tests cannot be retargeted"
ERROR_UNIT_JAVA="each Java module builds against the version its own pom resolves"

flowed_output() {
    printf '%s' "$output" | tr -s '[:space:]' ' '
}

assert_rejected() {
    # The Python half names the requirement actually installed, so it is built
    # from the pom here rather than pinned as a literal.
    local python_clause="the Python unit tests install apache-flink~=$(repo_flink_minor).0"
    case "$(flowed_output)" in
        *"$ERROR_REQUIRES_E2E"*"$ERROR_E2E_SCOPE"*"$ERROR_NO_RETARGET"*"$ERROR_UNIT_JAVA"*"$python_clause"*) ;;
        *) false ;;
    esac
}

assert_not_rejected() {
    case "$(flowed_output)" in *"$ERROR_REQUIRES_E2E"*) false ;; *) ;; esac
}

# Fails if $output demonstrates an invocation the script would itself exit 1 on:
# a -f or --flink form carrying no -e. Commas become newlines first, because one
# help line lists several forms and only one of them may be at fault; trailing
# `#` comments are dropped, so prose that happens to mention -e cannot vouch for
# the invocation beside it; and each form is whitespace-collapsed so -e is
# matched as its own argument rather than as a substring. The option's own
# `-f, --flink` line does not match: the pattern needs a version token after
# the flag.
assert_no_rejectable_f_example() {
    local form matched=0
    while IFS= read -r form; do
        [ -n "$form" ] || continue
        matched=$((matched + 1))
        form=" $(printf '%s' "$form" | tr -s '[:space:]' ' ') "
        case "$form" in *" -e "*|*" --e2e "*) ;; *) false ;; esac
    done <<EOF
$(printf '%s\n' "$output" | tr ',' '\n' | sed 's/#.*//' \
    | grep -E '(^|[[:space:]]|[(])(-f|--flink)[[:space:]]+[0-9]')
EOF
    # Without this the scan passes on output carrying no examples at all.
    [ "$matched" -ge 1 ]
}

@test "-f with the Java unit tests is rejected, on stderr" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -j -f 1.20
    [ "$status" -eq 1 ]
    assert_rejected
    # Dropping stderr must drop the message with it: an error on stdout would
    # land in the middle of test output that gets parsed or piped.
    run bash -c "'${FLINK_AGENTS_SUT_BASH:-bash}' '$UT_SH' -j -f 1.20 2>/dev/null"
    [ "$status" -eq 1 ]
    assert_not_rejected
}

@test "-f with only the Python tests is rejected too" {
    # The Python tests do install the version they are given, so this is the
    # combination a scope check written around the Java suite alone would let
    # through -- and outside -e it is just as inapplicable.
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -p -f 1.20
    [ "$status" -eq 1 ]
    assert_rejected
}

@test "the message names the requirement actually installed, derived from the pom" {
    # A hardcoded version matches the real repo today and rots at the next
    # bump, so drive the check against a tree pinning a different one. The
    # x.y.0 form is the point: ~= constrains only the x.y line, so the pom's
    # three-component value describes a precision the install does not have.
    local fake
    fake="$(make_fake_root_pinning 9.9.9 9.9)"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh" -p -f 9.9
    [ "$status" -eq 1 ]
    case "$(flowed_output)" in *"apache-flink~=9.9.0"*) ;; *) false ;; esac
    case "$(flowed_output)" in *"9.9.9"*) false ;; *) ;; esac
}

@test "-f is accepted with the e2e tests, and still selects the version they use" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -j -e -f 1.20
    [ "$status" -eq 0 ]
    assert_not_rejected
    # Exit 0 alone would still hold if -f had become a no-op, so pin the two
    # places the version reaches Maven: the dist module installed, and the
    # profile the e2e run activates.
    case "$(shim_calls mvn)" in *"dist/flink-1.20"*) ;; *) false ;; esac
    case "$(shim_calls mvn)" in *"-Pflink-1.20"*) ;; *) false ;; esac
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -p -e -f 1.20
    [ "$status" -eq 0 ]
    assert_not_rejected
    case "$(shim_calls uv)" in *"apache-flink~=1.20.0"*) ;; *) false ;; esac
}

@test "-e is honored after -f, not only before it" {
    # The guard reads the parse loop's final state rather than the order the
    # flags arrive in; folding it into the -f case branch would break this.
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -p -f 1.20 -e
    [ "$status" -eq 0 ]
    assert_not_rejected
    case "$(shim_calls uv)" in *"apache-flink~=1.20.0"*) ;; *) false ;; esac
}

@test "a run that passes no -f is not rejected over the defaulted version" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -j
    [ "$status" -eq 0 ]
    assert_not_rejected
}

@test "an unsupported version outside -e is reported as a scope error, not an unsupported one" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -p -f 9.9
    [ "$status" -eq 1 ]
    assert_rejected
    # No value of -f applies here, so naming 9.9 as the problem would send the
    # caller hunting for a supported version instead of adding -e.
    case "$output" in *"unsupported Flink version"*) false ;; *) ;; esac
}

@test "the rejection lands before any Maven or Python work starts" {
    # The point of rejecting at all is that it costs the caller no build, so
    # neither shim may have been reached by the time the script exits. Driven
    # with no suite flag, which selects both, so a rejection reached from only
    # one of the two suite paths cannot pass this.
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -f 1.20
    [ "$status" -eq 1 ]
    assert_rejected
    [ "$(shim_call_count mvn)" -eq 0 ]
    [ "$(shim_call_count uv)" -eq 0 ]
}

@test "the script demonstrates no -f form it would itself reject" {
    # Both places -f usage is advertised: the help text, and the error shown
    # when -f is given no version, which prints the help after it.
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" --help
    [ "$status" -eq 0 ]
    assert_no_rejectable_f_example
    run bash -c "'${FLINK_AGENTS_SUT_BASH:-bash}' '$UT_SH' -f 2>&1"
    [ "$status" -eq 1 ]
    assert_no_rejectable_f_example
}
