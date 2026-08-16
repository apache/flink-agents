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

BUILD_SCRIPT="${BATS_TEST_DIRNAME}/../../build.sh"

@test "build --help prints usage and exits 0" {
    run bash "$BUILD_SCRIPT" --help

    [ "$status" -eq 0 ]
    [[ "$output" == *"Build Flink Agents Java and Python artifacts"* ]]
    [[ "$output" == *"Usage:"* ]]
    [[ "$output" == *"--java"* ]]
    [[ "$output" == *"--python"* ]]
}

@test "build -h prints usage and exits 0" {
    run bash "$BUILD_SCRIPT" -h

    [ "$status" -eq 0 ]
    [[ "$output" == *"Usage:"* ]]
}

@test "build rejects an unknown option with usage" {
    run bash "$BUILD_SCRIPT" --no-such-option

    [ "$status" -eq 1 ]
    [[ "$output" == *"Error: Unknown option '--no-such-option'"* ]]
    [[ "$output" == *"Usage:"* ]]
    [[ "$output" != *"show_help: command not found"* ]]
}
