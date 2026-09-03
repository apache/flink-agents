#!/usr/bin/env bats

setup() {
    load '../helpers/shim'
    load '../helpers/fake_root'
    shim_setup
    UT_SH="${BATS_TEST_DIRNAME}/../../ut.sh"
    REPO_POM="${BATS_TEST_DIRNAME}/../../../pom.xml"
}

# Two-component Flink version the root pom pins, e.g. "2.3" for <flink.version>2.3.0.
repo_flink_minor() {
    local v
    v="$(sed -n 's/.*<flink.version>\(.*\)<\/flink.version>.*/\1/p' "$REPO_POM" | head -1)"
    echo "${v%.*}"
}

@test "--help does not claim every Flink version is tested by default" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" --help
    [ "$status" -eq 0 ]
    case "$output" in *"all versions"*) false ;; *) ;; esac
    case "$output" in *"all Flink versions"*) false ;; *) ;; esac
}

@test "--help states the default Flink version and which suites -f applies to" {
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" --help
    [ "$status" -eq 0 ]
    case "$output" in *"Default: $(repo_flink_minor)"*) ;; *) false ;; esac
    # The scope sentence wraps across help lines; compare on collapsed
    # whitespace so re-indenting or re-wrapping the block cannot break this.
    local flowed
    flowed="$(printf '%s' "$output" | tr -s '[:space:]' ' ')"
    case "$flowed" in
        *"Requires -e: it selects the Flink version the e2e tests run against"*) ;;
        *) false ;;
    esac
    case "$flowed" in *"The unit tests cannot be retargeted"*) ;; *) false ;; esac
    # Which version they use instead is the half most easily re-worded into
    # something false, so both suites are pinned: each Java module resolves
    # flink.version through its own pom, which most modules inherit from the
    # root and most dist ones override, and the Python suite installs the
    # requirement built from the default.
    case "$flowed" in
        *"each Java module builds against the version its own pom resolves"*) ;;
        *) false ;;
    esac
    case "$flowed" in
        *"Python unit tests install apache-flink~=$(repo_flink_minor).0"*) ;;
        *) false ;;
    esac
}

@test "--help builds the Python requirement from the pom rather than a literal" {
    # The assertion above reads the same pom the help renders from, so a
    # hardcoded literal satisfies it for as long as the pom agrees. Only a tree
    # pinning a different version separates interpolation from a literal.
    local fake flowed
    fake="$(make_fake_root_pinning 9.9.9 9.9)"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh" --help
    [ "$status" -eq 0 ]
    flowed="$(printf '%s' "$output" | tr -s '[:space:]' ' ')"
    case "$flowed" in
        *"Python unit tests install apache-flink~=9.9.0"*) ;;
        *) false ;;
    esac
}

@test "a bare Python run installs the Flink version the root pom pins" {
    shim_bin uv
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$UT_SH" -p
    [ "$status" -eq 0 ]
    case "$(shim_calls uv)" in
        *"apache-flink~=$(repo_flink_minor).0"*) ;;
        *) false ;;
    esac
}

@test "the default Flink version follows the pom rather than a literal" {
    shim_bin uv
    local fake
    # The fake tree carries the dist module its pom pins, so the defaulted
    # version is a supported one and only the value being read is under test.
    fake="$(make_fake_root '    <flink.version>9.9.9</flink.version>' 9.9)"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh" -p
    [ "$status" -eq 0 ]
    case "$(shim_calls uv)" in *"apache-flink~=9.9.0"*) ;; *) false ;; esac
}

@test "a whitespace-padded flink.version is read as the version it pads" {
    shim_bin uv
    local fake
    fake="$(make_fake_root '    <flink.version> 9.9.9 </flink.version>' 9.9)"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh" -p
    [ "$status" -eq 0 ]
    case "$(shim_calls uv)" in *"apache-flink~=9.9.0"*) ;; *) false ;; esac
}

@test "a pom carrying no flink.version at all is fatal" {
    local fake
    fake="$(make_fake_root '    <other.version>1.0.0</other.version>')"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh"
    [ "$status" -eq 1 ]
    # Nothing was read, so the error must not quote a value as though one was.
    case "$output" in
        *"Error: found no usable <flink.version> value in"*) ;;
        *) false ;;
    esac
    case "$output" in *"read '"*) false ;; *) ;; esac
}

@test "a two-component flink.version is fatal rather than a one-component token" {
    local fake
    fake="$(make_fake_root '    <flink.version>2.3</flink.version>')"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh"
    [ "$status" -eq 1 ]
    # The value was read; the error has to say so rather than claim it could not be.
    case "$output" in
        *"Error: read '2.3' as <flink.version>"*) ;;
        *) false ;;
    esac
    case "$output" in *"expected an x.y.z version"*) ;; *) false ;; esac
}

@test "a flink.version holding a property reference is fatal rather than a broken token" {
    local fake
    fake="$(make_fake_root '    <flink.version>${flink.2.3.version}</flink.version>')"
    run "${FLINK_AGENTS_SUT_BASH:-bash}" "$fake/tools/ut.sh"
    [ "$status" -eq 1 ]
    # A value is present, so the error has to quote it rather than report an
    # absent element the way the missing-property case does.
    case "$output" in
        *"Error: read '\${flink.2.3.version}' as <flink.version>"*) ;;
        *) false ;;
    esac
    case "$output" in *"expected an x.y.z version"*) ;; *) false ;; esac
}
