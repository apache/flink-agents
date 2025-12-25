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

# Default Flink version
DEFAULT_FLINK_VERSION="2.2"

# Default values
run_java=true
run_python=true
run_e2e=false
verbose=false
flink_versions=()

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
  -f, --flink       Specify Flink version to test (can be used multiple times)
                    Supported versions: 2.2, 1.20
                    Examples: -f 2.2, -f 1.20, -f 2.2 -f 1.20
                    Default: run all versions if not specified
  -v, --verbose     Show verbose output
  -h, --help        Display this help message

Examples:
  $0 --java         # Run only Java tests (all Flink versions)
  $0 -p             # Run only Python tests
  $0 -f 2.2         # Run tests only for Flink 2.2
  $0 -f 1.20        # Run tests only for Flink 1.20
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
        -f|--flink)
            if [[ -z "$2" || "$2" == -* ]]; then
                echo "Error: -f requires a version argument (e.g., -f 1.20)" >&2
                show_help
                exit 1
            fi
            flink_versions+=("$2")
            shift
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

# If no version is specified, the default version will be run by default.
if [ ${#flink_versions[@]} -eq 0 ]; then
    flink_versions=("${DEFAULT_FLINK_VERSION}")
fi

# Remove duplicates and sort version numbers
flink_versions=($(echo "${flink_versions[@]}" | tr ' ' '\n' | sort -u | tr '\n' ' '))

if $verbose; then
    echo "Will run tests for Flink versions: ${flink_versions[*]}"
fi

java_tests() {
    if $verbose; then
        echo "Running Java tests..."
    fi
    set +e
    echo "Executing Java test suite..."
    pushd "${ROOT}"
    if $run_e2e; then
        echo "Installing dist packages to local repository..."

        dist_modules=""
        for version in "${flink_versions[@]}"; do
            dist_modules="${dist_modules},dist/flink-${version}"
        done
        dist_modules="${dist_modules#,}"

        mvn --batch-mode --no-transfer-progress install -pl "$dist_modules" -DskipTests
        install_code=$?
        if [ $install_code -ne 0 ]; then
            echo "Failed to install dist packages" >&2
            return 1
        fi

        local all_passed=true
        for version in "${flink_versions[@]}"; do
            echo "Running E2E tests for Flink ${version}..."
            mvn --batch-mode --no-transfer-progress test -pl 'e2e-test/flink-agents-end-to-end-tests-integration' -Pflink-${version}

            if [ $? -ne 0 ]; then
                echo "E2E tests failed for Flink ${version}" >&2
                all_passed=false
            fi
        done

        if [ "$all_passed" = false ]; then
            return 1
        fi
        testcode=0
    else
        echo "Installing all modules (including test-jars) to local repository..."
        mvn --batch-mode --no-transfer-progress test-compile jar:test-jar install -DskipTests
        install_code=$?
        if [ $install_code -ne 0 ]; then
            echo "Failed to install modules to local repository" >&2
            return 1
        fi

        local all_passed=true

        for version in "${flink_versions[@]}"; do
            echo "Running tests for Flink ${version}..."
            if [ "$version" == "${DEFAULT_FLINK_VERSION}" ]; then
                exclude_list="!e2e-test/flink-agents-end-to-end-tests-integration,!e2e-test/flink-agents-end-to-end-tests-resource-cross-language"
                for version_dir in "${ROOT}"/dist/flink-*; do
                    if [ -d "$version_dir" ]; then
                      version_name=$(basename "$version_dir")
                      exclude_list="${exclude_list},!dist/${version_name}"
                    fi
                done

                mvn -T16 --batch-mode --no-transfer-progress test -pl "${exclude_list}"
                if [ $? -ne 0 ]; then
                    echo "Tests failed for Flink ${version}" >&2
                    all_passed=false
                fi
            else
                mvn -T16 --batch-mode --no-transfer-progress test -pl dist/flink-${version}
                if [ $? -ne 0 ]; then
                    echo "Tests failed for Flink ${version}" >&2
                    all_passed=false
                fi
            fi
        done

        if [ "$all_passed" = false ]; then
            testcode=1
        else
            testcode=0
        fi
    fi
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

    # Run tests for each Flink version
    local all_passed=true
    for version in "${flink_versions[@]}"; do
        echo "Running Python tests for Flink ${version}..."

        # Install dependencies and run tests
        if command -v uv >/dev/null 2>&1; then
            if $verbose; then
                echo "Using uv for dependency management"
            fi
            if $verbose; then
                echo "Running tests with uv for Flink ${version}..."
            fi
            if $run_e2e; then
                # There will be an individual build step before run e2e test for including java dist
                uv pip install apache-flink~=${version}.0
                uv run --no-sync pytest flink_agents -s -k "e2e_tests_integration"
            else
                uv sync --extra test
                uv pip install apache-flink~=${version}.0
                uv run --no-sync pytest flink_agents -k "not e2e_tests"
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
                pip install apache-flink~=${version}.0
            fi
            if $verbose; then
                echo "Running tests with pytest..."
            fi
            if $run_e2e; then
                pytest flink_agents -k "e2e_tests_integration"
            else
                pytest flink_agents -k "not e2e_tests"
            fi
            testcode=$?
        fi

        # Handle pytest exit codes
        case $testcode in
            0)  # All tests passed
                if $verbose; then
                    echo "Python tests passed for Flink ${version}"
                fi
                ;;
            1)  # Tests failed
                echo "Python tests failed for Flink ${version}" >&2
                all_passed=false
                ;;
            2)  # Test execution interrupted
                echo "Python tests interrupted for Flink ${version}" >&2
                all_passed=false
                ;;
            5)  # No tests collected
                echo "Warning: No Python tests collected for Flink ${version}" >&2
                ;;
            *)  # Unknown error
                echo "Python tests encountered unknown error for Flink ${version} (exit code: $testcode)" >&2
                all_passed=false
                ;;
        esac
    done

    popd

    if [ "$all_passed" = false ]; then
        return 2
    else
        return 0
    fi
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
