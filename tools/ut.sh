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

# Read the default Flink version from the root pom rather than pinning it here,
# so bumping the project's Flink version cannot leave this script testing an
# older line. The pom carries x.y.z; the version tokens used below are x.y.
# Anything that does not end up shaped x.y is fatal, because the value flows
# unvalidated into dist module paths, -P profile names and the pip requirement.
# Whitespace is stripped because XML permits padding inside the element and a
# version token carries none of its own.
POM_FLINK_VERSION="$(sed -n 's/.*<flink\.version>\([^<]*\)<\/flink\.version>.*/\1/p' "${ROOT}/pom.xml" 2>/dev/null | head -1 | tr -d '[:space:]')"
DEFAULT_FLINK_VERSION="${POM_FLINK_VERSION%.*}"
if [[ -z "${POM_FLINK_VERSION}" ]]; then
    echo "Error: found no usable <flink.version> value in ${ROOT}/pom.xml; the file must exist and pin an x.y.z version" >&2
    exit 1
fi
if [[ ! "${DEFAULT_FLINK_VERSION}" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Error: read '${POM_FLINK_VERSION}' as <flink.version> from ${ROOT}/pom.xml; expected an x.y.z version, not a property reference or a shorter version" >&2
    exit 1
fi

# The versions -f accepts are derived from the dist modules rather than listed
# here, because -f resolves to dist/flink-<version>: a value with no directory
# names a Maven module that does not exist. Glob expansion is already sorted,
# so the list is deterministic; the ordering is lexicographic rather than by
# version precedence, so a two-digit minor sorts ahead of a one-digit one.
SUPPORTED_FLINK_VERSIONS=()
for dist_dir in "${ROOT}"/dist/flink-*/; do
    [[ -d "${dist_dir}" ]] || continue
    dist_dir="${dist_dir%/}"
    SUPPORTED_FLINK_VERSIONS+=("${dist_dir##*/flink-}")
done
# Checked separately from the per-version validation below: with nothing in the
# set, every version fails that check, and its messages would blame the version
# under test for what is really a tree carrying no dist modules at all.
if [[ ${#SUPPORTED_FLINK_VERSIONS[@]} -eq 0 ]]; then
    echo "Error: found no dist/flink-* modules under ${ROOT}; the set of supported Flink versions is read from them and cannot be determined" >&2
    exit 1
fi

# bash 3.2 has no associative arrays, so membership is a scan.
is_supported_flink_version() {
    local candidate="$1" supported
    for supported in "${SUPPORTED_FLINK_VERSIONS[@]}"; do
        if [[ "${candidate}" == "${supported}" ]]; then
            return 0
        fi
    done
    return 1
}

# Default values
run_java=true
run_python=true
run_e2e=false
verbose=false
flink_versions=()
# Whether -f was passed rather than defaulted. Not recoverable from
# flink_versions afterwards: the default fill makes a defaulted array
# indistinguishable from an explicit request for the same version.
flink_explicit=false

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
                    Supported versions: ${SUPPORTED_FLINK_VERSIONS[*]}
                    Examples: -e -f 2.3, -e -f 1.20, -e -f 2.3 -f 1.20
                    Default: ${DEFAULT_FLINK_VERSION}, from flink.version in the root pom.xml
                    Requires -e: it selects the Flink version the e2e tests run
                    against. The unit tests cannot be retargeted; each Java
                    module builds against the version its own pom resolves, and
                    the Python unit tests install apache-flink~=${DEFAULT_FLINK_VERSION}.0.
  -v, --verbose     Show verbose output
  -h, --help        Display this help message

Examples:
  $0 --java         # Run only Java tests
  $0 -p             # Run only Python tests
  $0 -e -f 2.2      # Run the e2e tests against Flink 2.2
  $0 -e -f 1.20     # Run the e2e tests against Flink 1.20
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
                echo "Error: -f requires a version argument (e.g., -e -f 1.20)" >&2
                show_help
                exit 1
            fi
            flink_versions+=("$2")
            flink_explicit=true
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

# -f selects the Flink version the e2e tests run against, and nothing else
# reads it. Each Java module compiles and tests against the <flink.version> its
# own pom resolves: most inherit the root's, while most dist/flink-<v> modules
# override it with the matching flink.<v>.version, and those modules stay in
# the unit-test reactor because only the e2e modules are excluded from it. The
# Python unit tests install the requirement built from the default version. So
# outside -e the flag has nothing left to select.
# Checked before the version is validated below: when the flag does not apply
# at all, the value it carries is beside the point, and reporting that value as
# unsupported would send the caller looking for a different one.
if $flink_explicit && ! $run_e2e; then
    cat >&2 <<EOF
Error: -f requires -e; it selects the Flink version the e2e tests run against.
       The unit tests cannot be retargeted: each Java module builds against the
       version its own pom resolves, and the Python unit tests install
       apache-flink~=${DEFAULT_FLINK_VERSION}.0.
EOF
    exit 1
fi

# If no version is specified, the default version will be run by default.
if [ ${#flink_versions[@]} -eq 0 ]; then
    flink_versions=("${DEFAULT_FLINK_VERSION}")
fi

# Validated here rather than as each -f is parsed so that the defaulted value
# is checked too. The default and the accepted set are derived from different
# files -- the root pom's <flink.version> and the dist/ modules -- and nothing
# else makes them agree, so a pom bumped ahead of its dist modules would
# otherwise let a bare run reach a Maven module that does not exist.
# Which of the two is at fault decides the message: a value the caller typed
# is theirs to correct, while a defaulted one means the repo disagrees with
# itself and no choice of -f is the fix.
for version in "${flink_versions[@]}"; do
    if ! is_supported_flink_version "${version}"; then
        if $flink_explicit; then
            echo "Error: unsupported Flink version '${version}'; supported versions: ${SUPPORTED_FLINK_VERSIONS[*]}" >&2
        else
            echo "Error: the root pom pins <flink.version> ${POM_FLINK_VERSION}, but ${ROOT}/dist carries no flink-${version} module; dist modules exist for: ${SUPPORTED_FLINK_VERSIONS[*]}" >&2
        fi
        exit 1
    fi
done

# Remove duplicates and sort version numbers
flink_versions=($(echo "${flink_versions[@]}" | tr ' ' '\n' | sort -u | tr '\n' ' '))

if $verbose; then
    echo "Will run tests for Flink versions: ${flink_versions[*]}"
fi

# Skip spotless code-style check when SKIP_SPOTLESS_CHECK is set.
# Style enforcement is owned by the dedicated `Code Style Check` CI job
# (and `tools/lint.sh` locally), so other CI jobs append this flag to
# every mvn invocation to avoid masking real test failures with style
# violations. Unset (default) preserves local-dev behavior.
SPOTLESS_FLAG=""
if [ "${SKIP_SPOTLESS_CHECK}" = "true" ] || [ "${SKIP_SPOTLESS_CHECK}" = "1" ]; then
    SPOTLESS_FLAG="-Dspotless.skip=true"
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

        mvn --batch-mode --no-transfer-progress install -pl "$dist_modules" -DskipTests ${SPOTLESS_FLAG}
        install_code=$?
        if [ $install_code -ne 0 ]; then
            echo "Failed to install dist packages" >&2
            return 1
        fi

        local all_passed=true
        for version in "${flink_versions[@]}"; do
            echo "Running E2E tests for Flink ${version}..."
            mvn --batch-mode --no-transfer-progress test -pl 'e2e-test/flink-agents-end-to-end-tests-integration' -Pflink-${version} -Dsurefire.rerunFailingTestsCount=2 ${SPOTLESS_FLAG}

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
        mvn --batch-mode --no-transfer-progress test-compile jar:test-jar install -DskipTests ${SPOTLESS_FLAG}
        install_code=$?
        if [ $install_code -ne 0 ]; then
            echo "Failed to install modules to local repository" >&2
            return 1
        fi

        local all_passed=true

        exclude_list="!e2e-test/flink-agents-end-to-end-tests-integration,!e2e-test/flink-agents-end-to-end-tests-resource-cross-language"

        mvn -T16 --batch-mode --no-transfer-progress test -fae -pl "${exclude_list}" ${SPOTLESS_FLAG}
        testcode=$?
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
                # Arm 1: existing e2e tests (directory-based selector).
                uv run --no-sync pytest flink_agents \
                -s \
                -k "e2e_tests_integration" \
                --reruns 2 \
                --reruns-delay 5 \
                -o log_cli=true \
                -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                rc1=$?
                # Arm 2: integration-marked tests (registered in pyproject.toml).
                # Trap exit code 5 (no tests collected) as failure to defend
                # against -m selector typos that --strict-markers does not catch.
                uv run --no-sync pytest flink_agents \
                -s \
                -m "integration" \
                -o log_cli=true \
                -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                rc2=$?
                if [ $rc2 -eq 5 ]; then rc2=1; fi
                # Logical-OR aggregation: any nonzero exit on either arm yields testcode=1.
                # Side effect: pytest exit 5 (no tests collected) becomes failure on BOTH
                # arms, not just arm 2 — which is the correct semantics (zero collection
                # on either arm indicates a selector regression).
                testcode=$((rc1 || rc2))
            else
                uv sync --extra test
                uv pip install apache-flink~=${version}.0
                uv run --no-sync pytest flink_agents \
                -k "not e2e_tests" \
                -m "not integration" \
                -o log_cli=true \
                -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                testcode=$?
            fi
        else
            if $verbose; then
                echo "uv not found, falling back to pip"
            fi
            # Try modern pyproject.toml first, then fallback to requirements.txt
            if [ -f "pyproject.toml" ]; then
                if $verbose; then
                    echo "Using pyproject.toml dependency groups"
                fi
                python3 -m pip install -e ".[test]"
                python3 -m pip install apache-flink~=${version}.0
            fi
            if $verbose; then
                echo "Running tests with pytest..."
            fi
            if $run_e2e; then
                python3 -m pytest flink_agents -k "e2e_tests_integration" --reruns 2 --reruns-delay 5 -o log_cli=true -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                rc1=$?
                # Arm 2: integration-marked tests; trap exit code 5 as failure.
                python3 -m pytest flink_agents -m "integration" -o log_cli=true -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                rc2=$?
                if [ $rc2 -eq 5 ]; then rc2=1; fi
                # Logical-OR aggregation: any nonzero exit on either arm yields testcode=1.
                # Side effect: pytest exit 5 (no tests collected) becomes failure on BOTH
                # arms, not just arm 2 — which is the correct semantics (zero collection
                # on either arm indicates a selector regression).
                testcode=$((rc1 || rc2))
            else
                python3 -m pytest flink_agents -k "not e2e_tests" -m "not integration" -o log_cli=true -o log_cli_level=${LOG_LEVEL:-CRITICAL}
                testcode=$?
            fi
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
