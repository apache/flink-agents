#!/usr/bin/env bats

@test "--help prints usage and exits 0" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" --help
    [ "$status" -eq 0 ]
    case "$output" in *"Apache Flink Agents Installer"*) ;; *) false ;; esac
    case "$output" in *"Options:"*) ;; *) false ;; esac
    case "$output" in *"--install-flink"*) ;; *) false ;; esac
    case "$output" in *"--dry-run"*) ;; *) false ;; esac
}

@test "-h prints usage and exits 0" {
    run bash "${BATS_TEST_DIRNAME}/../../install.sh" -h
    [ "$status" -eq 0 ]
    case "$output" in *"Apache Flink Agents Installer"*) ;; *) false ;; esac
}
