#!/usr/bin/env bats

################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

setup() {
    load '../helpers/shim'
    shim_setup
    CHECK_LICENSE_SOURCE_ONLY=1
    source "${BATS_TEST_DIRNAME}/../../check-license.sh"
    unset CHECK_LICENSE_SOURCE_ONLY


    RAT_VERSION="test"
    rat_jar="$BATS_TEST_TMPDIR/apache-rat-test.jar"
    JAR="$rat_jar"
    JAVA_HOME="$BATS_TEST_TMPDIR/missing-java-home"
}

@test "reports missing validation tools without deleting an existing JAR" {
    touch "$rat_jar"
    shim_bin_missing jar
    shim_bin_missing unzip

    run acquire_rat_jar

    [ "$status" -eq 0 ]
    [[ "$output" == *"Warning: cannot validate cached Apache RAT JAR"* ]]
    [ -f "$rat_jar" ]
}

@test "rejects and removes an invalid existing JAR" {
    touch "$rat_jar"
    shim_bin_missing jar
    shim_bin unzip 1

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"is invalid"* ]]
    [ ! -f "$rat_jar" ]
}

@test "treats unzip status 2 as an invalid cached JAR" {
    touch "$rat_jar"
    shim_bin_missing jar
    shim_bin unzip 2

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"is invalid"* ]]
    [ ! -f "$rat_jar" ]
}

@test "does not download an existing valid JAR" {
    touch "$rat_jar"
    shim_bin_missing jar
    shim_bin unzip
    shim_bin curl

    acquire_rat_jar

    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count unzip)" = "1" ]
}

@test "reports a download failure and removes the partial file" {
    shim_bin_script curl 'prev=""; for arg in "$@"; do [[ "$prev" == "--output" || "$prev" == "-o" ]] && : > "$arg"; prev="$arg"; done; exit 22'

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"Failed to download Apache RAT"* ]]
    [ ! -e "${rat_jar}.part" ]
    [ ! -e "$rat_jar" ]
}

@test "downloads with curl safety flags and validates the result" {
    shim_bin_script curl 'prev=""; for arg in "$@"; do if [[ "$prev" == "--output" || "$prev" == "-o" ]]; then touch "$arg"; exit 0; fi; prev="$arg"; done; exit 64'
    shim_bin_missing jar
    shim_bin unzip

    acquire_rat_jar

    [ -f "$rat_jar" ]
    run cat "$SHIM_CALLS/curl.log"
    [[ "$output" == *"--fail"* ]]
    [[ "$output" == *"--show-error"* ]]
    [[ "$output" == *"--location"* ]]
    [ "$(shim_call_count unzip)" = "1" ]
}

@test "fails closed when a downloaded JAR cannot be validated" {
    shim_bin_script curl 'prev=""; for arg in "$@"; do if [[ "$prev" == "--output" || "$prev" == "-o" ]]; then : > "$arg"; fi; prev="$arg"; done'
    shim_bin_missing jar
    shim_bin_missing unzip

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"Cannot validate the downloaded Apache RAT JAR"* ]]
    [ ! -e "$rat_jar" ]
    [ ! -e "${rat_jar}.part" ]
}

@test "validates a cached JAR with jar when unzip is unavailable" {
    touch "$rat_jar"
    shim_bin_missing unzip
    shim_bin jar

    run acquire_rat_jar

    [ "$status" -eq 0 ]
    [ "$(shim_call_count jar)" = "1" ]
    [ -f "$rat_jar" ]
}

@test "rejects an invalid cached JAR with jar when unzip is unavailable" {
    touch "$rat_jar"
    shim_bin_missing unzip
    shim_bin jar 1

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"is invalid"* ]]
    [ ! -f "$rat_jar" ]
}

@test "treats jar status 2 as an invalid cached JAR" {
    touch "$rat_jar"
    shim_bin_missing unzip
    shim_bin jar 2

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"is invalid"* ]]
    [ ! -f "$rat_jar" ]
}

@test "prefers unzip when both validation tools are available" {
    touch "$rat_jar"
    shim_bin unzip
    shim_bin jar 1

    run acquire_rat_jar

    [ "$status" -eq 0 ]
    [ "$(shim_call_count unzip)" = "1" ]
    [ "$(shim_call_count jar)" = "0" ]
}
