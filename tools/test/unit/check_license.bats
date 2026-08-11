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

command() {
    if [[ "$1" == "-v" ]]; then
        local missing
        for missing in "${MISSING_COMMANDS[@]:-}"; do
            if [[ "$2" == "$missing" ]]; then
                return 1
            fi
        done
    fi
    builtin command "$@"
}

shim_bin() {
    local name="$1" exit_code="${2:-0}"
    cat >"$SHIM_DIR/$name" <<EOF
#!/usr/bin/env bash
( IFS=\$'\t'; printf '%s\n' "\$*" ) >> "$SHIM_CALLS/$name.log"
exit $exit_code
EOF
    chmod +x "$SHIM_DIR/$name"
}

shim_bin_script() {
    local name="$1" body="$2"
    cat >"$SHIM_DIR/$name" <<EOF
#!/usr/bin/env bash
( IFS=\$'\t'; printf '%s\n' "\$*" ) >> "$SHIM_CALLS/$name.log"
$body
EOF
    chmod +x "$SHIM_DIR/$name"
}

shim_bin_missing() {
    MISSING_COMMANDS+=("$1")
}

shim_call_count() {
    local log="$SHIM_CALLS/$1.log"
    if [[ ! -f "$log" ]]; then
        echo 0
        return
    fi
    wc -l <"$log" | tr -d ' '
}

setup() {
    CHECK_LICENSE_SOURCE_ONLY=1
    source "${BATS_TEST_DIRNAME}/../../check-license.sh"
    unset CHECK_LICENSE_SOURCE_ONLY

    SHIM_DIR="$BATS_TEST_TMPDIR/bin"
    SHIM_CALLS="$BATS_TEST_TMPDIR/calls"
    MISSING_COMMANDS=()
    mkdir -p "$SHIM_DIR" "$SHIM_CALLS"
    export PATH="$SHIM_DIR:$PATH"

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

    [ "$status" -ne 0 ]
    [[ "$output" == *"install a JDK with 'jar' or install 'unzip'"* ]]
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
    shim_bin curl 22

    run acquire_rat_jar

    [ "$status" -ne 0 ]
    [[ "$output" == *"Failed to download Apache RAT"* ]]
    [ ! -e "${rat_jar}.part" ]
    [ ! -e "$rat_jar" ]
}

@test "downloads with curl safety flags and validates the result" {
    shim_bin_script curl 'while [[ "$1" != "--output" ]]; do shift; done; touch "$2"'
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
