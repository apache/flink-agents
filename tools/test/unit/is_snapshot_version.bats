#!/usr/bin/env bats

# is_snapshot_version returns 0 (true) for any non-release Flink version
# string — anything bearing a "-SNAPSHOT", "-rc", "-dev", etc. suffix.
# Pure release versions like 2.2.0 return 1 (false).
#
# Used by setup_python_env to decide whether to ask PyPI for an exact
# match (apache-flink==X.Y.Z) or a compatible release (apache-flink~=X.Y.0)
# when the user is running against a source-built Flink.

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "is_snapshot_version: SNAPSHOT suffix → true" {
    run is_snapshot_version "2.2-SNAPSHOT"
    [ "$status" -eq 0 ]
    run is_snapshot_version "2.1.0-SNAPSHOT"
    [ "$status" -eq 0 ]
}

@test "is_snapshot_version: rc / beta / dev suffix → true" {
    run is_snapshot_version "2.0.0-rc1"
    [ "$status" -eq 0 ]
    run is_snapshot_version "1.20.3-beta-2"
    [ "$status" -eq 0 ]
    run is_snapshot_version "2.1.0-dev"
    [ "$status" -eq 0 ]
}

@test "is_snapshot_version: plain release versions → false" {
    run is_snapshot_version "2.2.0"
    [ "$status" -eq 1 ]
    run is_snapshot_version "1.20.3"
    [ "$status" -eq 1 ]
}

@test "is_snapshot_version: empty string → false (defensive)" {
    run is_snapshot_version ""
    [ "$status" -eq 1 ]
}
