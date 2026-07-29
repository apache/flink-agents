#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    shim_setup
    # Fake FLINK_HOME so plan_flink (with INSTALL_FLINK=No) passes validation.
    export FLINK_HOME="$BATS_TEST_TMPDIR/flink-home"
    mkdir -p "$FLINK_HOME/lib"
    # Fake `java` so check_java passes.
    shim_bin_script java "
case \"\$1\" in
    -version) echo 'openjdk version \"17.0.2\"' >&2 ;;
esac
"
}

@test "--dry-run --non-interactive prints plan and makes no external calls" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"Installation plan"*) ;; *) false ;; esac
    case "$output" in *"Dry run complete"*) ;; *) false ;; esac
    # No downloader should have been invoked.
    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count wget)" = "0" ]
}

@test "--dry-run --install-flink --non-interactive shows Install Flink: Yes" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --install-flink --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"Install Flink"*) ;; *) false ;; esac
    case "$output" in *"Yes"*) ;; *) false ;; esac
    case "$output" in *"Dry run complete"*) ;; *) false ;; esac
}

@test "INSTALL_DIR=. does not produce a double-slash FLINK_HOME (review feedback guard)" {
    cd "$BATS_TEST_TMPDIR"
    run env INSTALL_DIR="." bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --install-flink --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *".//flink-"*) false ;; *) ;; esac
}

@test "INSTALL_DIR with trailing slash does not produce double-slash (review #7b)" {
    run env INSTALL_DIR="/tmp/flink-test/" bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --install-flink --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"//"*) false ;; *) ;; esac
    case "$output" in *"/tmp/flink-test"*) ;; *) false ;; esac
}

@test "INSTALL_DIR with consecutive slashes is collapsed (review #7b)" {
    run env INSTALL_DIR="/tmp//flink-test" bash "${BATS_TEST_DIRNAME}/../../install.sh" --dry-run --install-flink --non-interactive
    [ "$status" -eq 0 ]
    case "$output" in *"//flink-"*) false ;; *) ;; esac
}
