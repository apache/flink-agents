#!/usr/bin/env bash
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
set -e
set -x

ROOT="$(git rev-parse --show-toplevel)"
echo "Root path: $ROOT, home path: $HOME"
cd "$ROOT"

case $1 in
  java)
    echo "Executing java tests"
    set +e
    mvn -T16 --batch-mode --no-transfer-progress test
    testcode=$?
    if [[ $testcode -ne 0 ]]; then
      exit $testcode
    fi
    echo "Executing java tests succeeds"
    ;;
  python)
    echo "Executing python tests"
    set +e
    pip install -r python/requirements/test_requirements.txt
    pytest python/flink_agents
    testcode=$?
    if [[ $testcode -ne 0 && testcode -ne 5 ]]; then
      exit $testcode
    fi
    echo "Executing python tests succeeds"
    ;;
  *)
    echo "Execute command $*"
    "$@"
    ;;
esac
