#    Licensed to the Apache Software Foundation (ASF) under one
#   or more contributor license agreements.  See the NOTICE file
#   distributed with this work for additional information
#   regarding copyright ownership.  The ASF licenses this file
#   to you under the Apache License, Version 2.0 (the
#   "License"); you may not use this file except in compliance
#   with the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#  limitations under the License.
#

DEFAULT_FLINK_VERSION="2.2"

function run_test {
  local description="$1"
  local command="$2"

  printf "\n==============================================================================\n"
  printf "Running '${description}'\n"
  printf "==============================================================================\n"

  TOTAL=`expr $TOTAL + 1`

  ${command}
  exit_code="$?"

  if [[ ${exit_code} == 0 ]]; then
      printf "\n[PASS] '${description}' passed! Test exited with exit code 0.\n\n"
      PASSED=`expr $PASSED + 1`
  else
      printf "\n[FAIL] '${description}' failed! Test exited with exit code ${exit_code}\n\n"
  fi
}


function run_agent_plan_compatibility_test {
  if [[ ! -d "$python_dir" ]]; then
    echo "Error: Python directory '$python_dir' does not exist. Skipping test."
    return 1
  fi

  cd "$python_dir" && uv run --no-sync bash ../e2e-test/test-scripts/test_agent_plan_compatibility.sh "$tempdir" "$jar_path"
}

function run_cross_language_config_test {
  if [[ ! -d "$python_dir" ]]; then
    echo "Error: Python directory '$python_dir' does not exist. Skipping test."
    return 1
  fi

  cd "$python_dir" && uv run --no-sync bash ../e2e-test/test-scripts/test_java_config_in_python.sh
}

function run_resource_cross_language_test_in_java {
  if [[ ! -d "$python_dir" ]]; then
    echo "Error: Python directory '$python_dir' does not exist. Skipping test."
    return 1
  fi

  cd "$python_dir"

  # Get Python version from uv's virtual environment and set PYTHONPATH
  local python_version=$(uv run --no-sync python -c "import sys; print(f'python{sys.version_info.major}.{sys.version_info.minor}')" 2>/dev/null)
  if [[ -n "$python_version" ]]; then
    export PYTHONPATH="$python_dir/.venv/lib/$python_version/site-packages:$PYTHONPATH"
    echo "PYTHONPATH set to: $PYTHONPATH"
  fi

  uv run --no-sync bash ../e2e-test/test-scripts/test_resource_cross_language.sh
}

function run_resource_cross_language_test_in_python {
  if [[ ! -d "$python_dir" ]]; then
      echo "Error: Python directory '$python_dir' does not exist. Skipping test."
      return 1
    fi

    cd "$python_dir" && uv run --no-sync pytest flink_agents -s -k "e2e_tests_resource_cross_language"
}

export TOTAL=0
export PASSED=0

if [[ ! -d "e2e-test/target" ]]; then
  echo "Build flink-agents before run e2e tests."
  bash tools/build.sh
fi

# Ensure Python environment is properly set up with uv
if [[ ! -d "python" ]]; then
  echo "Error: Python directory does not exist. Please ensure the project structure is correct."
  exit 1
fi

cd python
if [[ ! -f "uv.lock" ]]; then
  echo "Python dependencies not installed. Running build.sh..."
  cd ..
  bash tools/build.sh
  if [[ ! -d "python" ]]; then
    echo "Error: Python directory still does not exist after build. Exiting."
    exit 1
  fi
  cd python
fi

cd ..

# Create temporary directory with better cross-platform compatibility
if command -v mktemp >/dev/null 2>&1; then
  tempdir=$(mktemp -d)
else
  # Fallback for systems without mktemp
  tempdir="/tmp/flink_agents_e2e_$$_$(date +%s)"
  mkdir -p "$tempdir"
fi
echo "tmpdir：$tempdir"

# Get absolute paths to avoid relative path issues
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"

# Find jar file more robustly
jar_files=("$project_root"/e2e-test/flink-agents-end-to-end-tests-agent-plan-compatibility/target/flink-agents-end-to-end-tests-agent-plan-compatibility-*.jar)
if [[ ${#jar_files[@]} -eq 0 ]] || [[ ! -f "${jar_files[0]}" ]]; then
  echo "Error: Could not find jar file in e2e-test/agent-plan-compatibility-test/target/"
  exit 1
fi
jar_path="${jar_files[0]}"

python_dir="$project_root/python"

# Verify python directory exists before running tests
if [[ ! -d "$python_dir" ]]; then
  echo "Error: Python directory '$python_dir' does not exist. Skipping tests."
  printf "\n0/2 bash e2e-tests passed (skipped due to missing python directory)\n"
  exit 1
fi

cd "$python_dir"
uv pip install apache-flink~=${DEFAULT_FLINK_VERSION}.0

run_test "Resource Cross-Language end-to-end test in Java" "run_resource_cross_language_test_in_java"
run_test "Resource Cross-Language end-to-end test in Python" "run_resource_cross_language_test_in_python"
run_test "Agent plan compatibility end-to-end test" "run_agent_plan_compatibility_test"
run_test "Cross-Language Config Option end-to-end test" "run_cross_language_config_test"

# Clean up temporary directory
if [[ -d "$tempdir" ]]; then
  rm -rf "$tempdir"
fi

printf "\n$PASSED/$TOTAL bash e2e-tests passed\n"

if [[ "$PASSED" != "$TOTAL" ]]; then
    exit 1
fi
