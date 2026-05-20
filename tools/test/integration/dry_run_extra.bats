#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    shim_setup
    export FLINK_HOME="$BATS_TEST_TMPDIR/flink-home"
    mkdir -p "$FLINK_HOME/lib" "$FLINK_HOME/bin"
    # Seed a fake flink-dist jar so detect_flink_version_from_home succeeds
    # and the plan reflects the on-disk version (review feedback #2 + #3).
    : > "$FLINK_HOME/lib/flink-dist-2.1.1.jar"
    shim_bin_script java "
case \"\$1\" in
    -version) echo 'openjdk version \"17.0.2\"' >&2 ;;
esac
"
}

@test "dry-run: plan shows Flink Agents version (review #1 — JARs are implicit)" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"Flink Agents version"*"0.2.1"*) ;; *) false ;; esac
}

@test "dry-run: existing FLINK_HOME — plan shows detected version, not default (review #2)" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"v2.1.1"*) ;; *) false ;; esac
}

@test "dry-run: INSTALL_FLINK=No suppresses Install directory line (review #3)" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"Install directory"*) false ;; *) ;; esac
}

@test "dry-run: Environment section is shown separately from Plan (review #5)" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"Environment (read-only)"*) ;; *) false ;; esac
    case "$output" in *"Installation plan"*) ;; *) false ;; esac
}
