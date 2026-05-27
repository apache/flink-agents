#!/usr/bin/env bats

# When a command fails under `set -e` (i.e. NOT through die()/die_cancelled),
# the ERR trap should print a banner that names the stage, the command,
# the install.sh line, and the exit code. die() must keep its existing
# single-line message and not trip the banner.

@test "on_error: set -e failure triggers a stage-aware banner" {
    # Use a real failure mode: pretend a Flink download 404'd. The script
    # walks through plan_flink with INSTALL_FLINK=Yes (default INSTALL_DIR,
    # but we override it to /tmp) and then dies on curl in stage 3.
    local tmp="$BATS_TEST_TMPDIR/demo"
    mkdir -p "$tmp"
    run env INSTALL_DIR="$tmp" FLINK_BASE_URL="https://dlcdn.apache.org/flink" \
        FLINK_VERSION=99.99.0 \
        bash "${BATS_TEST_DIRNAME}/../../install.sh" \
            --install-flink --non-interactive
    [ "$status" -ne 0 ]
    # Banner must name the stage by title.
    case "$output" in *"Installation failed at stage 3/5"*) ;; *) false ;; esac
    case "$output" in *"Installing Apache Flink"*) ;; *) false ;; esac
    # Banner must include source line + exit code keywords.
    case "$output" in *"Source:"*"install.sh:"*) ;; *) false ;; esac
    case "$output" in *"Exit code:"*) ;; *) false ;; esac
    # Banner must not point at the trap line itself, which would be a
    # regression — we want the line where the failing command lives.
    case "$output" in *"install.sh:0"*) false ;; *) ;; esac
}

@test "die(): single-line message, NO banner duplication" {
    # die() uses `exit`, which doesn't trigger ERR. Reach it by giving
    # plan_flink a non-existent FLINK_HOME under --non-interactive.
    run env FLINK_HOME="" \
        bash "${BATS_TEST_DIRNAME}/../../install.sh" --non-interactive
    [ "$status" -ne 0 ]
    case "$output" in *"FLINK_HOME is not set"*) ;; *) false ;; esac
    # Banner box characters must NOT appear in a die() path.
    case "$output" in *"Installation failed at stage"*) false ;; *) ;; esac
}
