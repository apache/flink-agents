#!/usr/bin/env bats

setup() {
    load '../helpers/shim'
    load '../helpers/fake_root'
    shim_setup
    UT_SH="${BATS_TEST_DIRNAME}/../../ut.sh"
    ROOT="${BATS_TEST_DIRNAME}/../../.."
    E2E_POM="${ROOT}/e2e-test/flink-agents-end-to-end-tests-integration/pom.xml"
    DIST_POM="${ROOT}/dist/pom.xml"
}

# The versions the repo actually carries a dist module for, sorted, one per line.
dist_versions() {
    local d
    for d in "${ROOT}"/dist/flink-*/; do
        basename "$d" | sed 's/^flink-//'
    done | sort
}

# The flink-* profile ids the e2e integration pom declares, sorted, one per line.
# The java-21 profile is excluded by the flink- prefix.
e2e_profile_versions() {
    sed -n 's/.*<id>flink-\(.*\)<\/id>.*/\1/p' "$E2E_POM" | sort
}

# The flink-* modules dist/pom.xml builds, sorted, one per line. The common
# module is excluded by the flink- prefix.
dist_module_versions() {
    sed -n 's/.*<module>flink-\(.*\)<\/module>.*/\1/p' "$DIST_POM" | sort
}

@test "a Flink version with no dist module is rejected before any test runs, on stderr" {
    shim_bin mvn
    shim_bin uv
    run bash "$UT_SH" -e -f 9.9
    [ "$status" -eq 1 ]
    case "$output" in *"Error: unsupported Flink version '9.9'"*) ;; *) false ;; esac
    # The message has to carry the way out of the mistake, not just report it.
    case "$output" in *"$(dist_versions | tr '\n' ' ' | sed 's/ $//')"*) ;; *) false ;; esac
    # Nothing may run: the point of validating before the suites start is that
    # a typo costs no build.
    [ "$(shim_call_count mvn)" -eq 0 ]
    [ "$(shim_call_count uv)" -eq 0 ]
    run bash -c "bash '$UT_SH' -e -f 9.9 2>/dev/null"
    case "$output" in *"unsupported Flink version"*) false ;; *) ;; esac
}

@test "every version the repo carries a dist module for is accepted" {
    shim_bin uv
    local version
    while read -r version; do
        run bash "$UT_SH" -p -e -f "$version"
        [ "$status" -eq 0 ]
    done < <(dist_versions)
}

@test "--help lists the dist modules that exist rather than a literal" {
    local fake
    fake="$(make_fake_root_pinning 3.0.0 3.0 4.1)"
    run bash "$fake/tools/ut.sh" --help
    [ "$status" -eq 0 ]
    case "$output" in *"Supported versions: 3.0 4.1"*) ;; *) false ;; esac
}

@test "the versions -f accepts follow the dist modules that exist rather than a fixed list" {
    # --help only proves the text the user is shown follows dist/; a validation
    # list pinned to today's repo would still pass that. Drive the check itself
    # against a tree whose dist modules disagree with the real one: the version
    # that exists only there has to be accepted, and the one that exists only
    # in the real repo has to be rejected.
    shim_bin uv
    local fake
    fake="$(make_fake_root_pinning 3.0.0 3.0 4.1)"
    run bash "$fake/tools/ut.sh" -p -e -f 4.1
    [ "$status" -eq 0 ]
    run bash "$fake/tools/ut.sh" -p -e -f 1.20
    [ "$status" -eq 1 ]
    case "$output" in *"Error: unsupported Flink version '1.20'"*) ;; *) false ;; esac
    case "$output" in *"supported versions: 3.0 4.1"*) ;; *) false ;; esac
}

@test "a defaulted version with no dist module is rejected, naming the pom rather than the caller" {
    # The default and the accepted set are read from different files, so a pom
    # bumped ahead of dist/ makes a bare run name a Maven module that does not
    # exist. Nothing the caller typed is wrong here, so the message must not
    # report an unsupported choice or offer -f as the way out.
    shim_bin mvn
    shim_bin uv
    local fake
    fake="$(make_fake_root_pinning 9.9.9 3.0 4.1)"
    run bash "$fake/tools/ut.sh"
    [ "$status" -eq 1 ]
    case "$output" in *"the root pom pins <flink.version> 9.9.9"*) ;; *) false ;; esac
    case "$output" in *"carries no flink-9.9 module"*) ;; *) false ;; esac
    case "$output" in *"unsupported Flink version"*) false ;; *) ;; esac
    [ "$(shim_call_count mvn)" -eq 0 ]
    [ "$(shim_call_count uv)" -eq 0 ]
}

@test "a tree carrying no dist module names that as the fault rather than the version" {
    # With no dist/flink-* at all the supported set is empty, so the per-version
    # messages would report the version under test as the problem and trail off
    # into an empty list of alternatives. Neither describes what is wrong.
    shim_bin mvn
    shim_bin uv
    local fake
    fake="$(make_fake_root_pinning 2.3.0)"
    run bash "$fake/tools/ut.sh"
    [ "$status" -eq 1 ]
    case "$output" in *"Error: found no dist/flink-* modules under"*) ;; *) false ;; esac
    case "$output" in *"carries no flink-2.3 module"*) false ;; *) ;; esac
    case "$output" in *"unsupported Flink version"*) false ;; *) ;; esac
    # The shape the two per-version messages degrade to when the set is empty:
    # a colon introducing a list, closing the message with nothing after it.
    case "$output" in *":" | *": ") false ;; *) ;; esac
    [ "$(shim_call_count mvn)" -eq 0 ]
    [ "$(shim_call_count uv)" -eq 0 ]
}

@test "every dist module directory is built and has a matching e2e Flink profile" {
    # -f <version> resolves to dist/flink-<version>, to that module's entry in
    # dist/pom.xml, and to -Pflink-<version>. A version present on one side
    # only is silently wrong: a directory dist/pom.xml does not list is never
    # built, a missing dist module fails the reactor, and a missing profile
    # leaves Maven to warn about an unactivatable profile and fall through to
    # the pom's own default.
    [ "$(dist_versions)" = "$(dist_module_versions)" ]
    [ "$(dist_versions)" = "$(e2e_profile_versions)" ]
}
