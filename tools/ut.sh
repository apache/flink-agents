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

ROOT="$(cd "$( dirname "$0" )" && pwd)/.."

# Default values
run_java=true
run_python=true
run_e2e=false
verbose=false

# Help information
show_help() {
    cat <<EOF
Run Java and Python test suites

Usage: $0 [options]

Options:
  -j, --java        Run only Java tests
  -p, --python      Run only Python tests
  -e, --e2e         Run e2e tests
  -b, --both        Run both Java and Python tests (default)
  -v, --verbose     Show verbose output
  -h, --help        Display this help message

Examples:
  $0 --java         # Run only Java tests
  $0 -p             # Run only Python tests
  $0 -v             # Run all tests with verbose output

Exit codes:
  0   All tests passed
  1   Java tests failed
  2   Python tests failed
  3   All tests failed
EOF
}

# Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -j|--java)
            run_java=true
            run_python=false
            ;;
        -p|--python)
            run_python=true
            run_java=false
            ;;
        -b|--both)
            run_java=true
            run_python=true
            ;;
        -e|--e2e)
            run_e2e=true
            ;;
        -v|--verbose)
            verbose=true
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo "Error: Unknown option '$1'" >&2
            show_help
            exit 1
            ;;
    esac
    shift
done

java_tests() {
    if $verbose; then
        echo "Running Java tests..."
    fi
    set +e
    echo "Executing Java test suite..."
    pushd "${ROOT}"
    if $run_e2e; then
        mvn -T16 --batch-mode --no-transfer-progress test -pl 'e2e-test/flink-agents-end-to-end-tests-integration'
    else
        mvn -T16 --batch-mode --no-transfer-progress test -pl '!e2e-test/flink-agents-end-to-end-tests-integration'
    fi
    testcode=$?
    case $testcode in
        0)  # All tests passed
            if $verbose; then
                echo "All Java tests passed"
            fi
            return 0
            ;;
        1)  # Tests failed
            echo "Java tests failed" >&2
            return 1
            ;;
        2)  # Test execution interrupted
            echo "Java tests interrupted" >&2
            return 1
            ;;
        *)  # Unknown error
            echo "Java tests encountered unknown error (exit code: $testcode)" >&2
            return 2
            ;;
    esac
}

python_tests() {
    if $verbose; then
        echo "Running Python tests..."
    fi

    set +e
    pushd "${ROOT}"/python
    
    # Install dependencies and run tests
    echo "Installing Python test dependencies..."
    if command -v uv >/dev/null 2>&1; then
        if $verbose; then
            echo "Using uv for dependency management"
        fi
        if $verbose; then
            echo "Running tests with uv..."
        fi
        if $run_e2e; then
            # There will be an individual build step before run e2e test for including java dist
            uv run --no-sync pytest flink_agents -s -k "e2e_tests"
        else
            uv sync --extra test
            uv run pytest flink_agents -k "not e2e_tests"
        fi
        testcode=$?
    else
        if $verbose; then
            echo "uv not found, falling back to pip"
        fi
        # Try modern pyproject.toml first, then fallback to requirements.txt
        if [ -f "pyproject.toml" ]; then
            if $verbose; then
                echo "Using pyproject.toml dependency groups"
            fi
            pip install -e ".[test]"
        else
            if $verbose; then
                echo "Using legacy requirements.txt"
            fi
            pip install -r requirements/test_requirements.txt
        fi
        if $verbose; then
            echo "Running tests with pytest..."
        fi
        if $run_e2e; then
            pytest flink_agents -k "e2e_tests"
        else
            pytest flink_agents -k "not e2e_tests"
        fi
        testcode=$?
    fi
    
    popd

    # Handle pytest exit codes
    case $testcode in
        0)  # All tests passed
            if $verbose; then
                echo "All Python tests passed"
            fi
            return 0
            ;;
        1)  # Tests failed
            echo "Python tests failed" >&2
            return 2
            ;;
        2)  # Test execution interrupted
            echo "Python tests interrupted" >&2
            return 2
            ;;
        5)  # No tests collected
            echo "Warning: No Python tests collected" >&2
            return 0  # Treat as success
            ;;
        *)  # Unknown error
            echo "Python tests encountered unknown error (exit code: $testcode)" >&2
            return 2
            ;;
    esac
}

main() {
    local java_result=0
    local python_result=0

    # Execute Java tests if enabled
    if $run_java; then
        java_tests
        java_result=$?
    fi

    # Execute Python tests if enabled
    if $run_python; then
        python_tests
        python_result=$?
    fi

    # Aggregate results
    if [[ $java_result -ne 0 && $python_result -ne 0 ]]; then
        echo "### ALL TESTS FAILED ###" >&2
        return 3
    elif [[ $java_result -ne 0 ]]; then
        echo "### JAVA TESTS FAILED ###" >&2
        return 1
    elif [[ $python_result -ne 0 ]]; then
        echo "### PYTHON TESTS FAILED ###" >&2
        return 2
    else
        echo "### ALL TESTS PASSED ###"
        return 0
    fi
}

# Execute main function and capture exit code
main
exit_code=$?

# Show final exit code in verbose mode
if $verbose; then
    echo "Final exit code: $exit_code"
fi

exit $exit_code
