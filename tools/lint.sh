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

format_code=true

# Help information
show_help() {
    cat <<EOF
Format/Check code files

Usage: $0 [options]

Options:
  -f, --format      Format code files. (default)
  -c, --check       Check code files for errors.
  -h, --help        Display this help message

Examples:
  $0 --format       # Format code files
  $0 -c             # Check code files for errors
EOF
}

# Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -f|--format)
            format_code=true
            ;;
        -c|--check)
            format_code=false
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

# Function to install Python dependencies
install_python_deps() {
  echo "Installing Python lint dependencies..."
  # Check if uv is available
  if command -v uv >/dev/null 2>&1; then
    echo "Using uv for dependency management"
    pushd python
    uv sync --extra lint
    popd
  else
    echo "uv not found, falling back to pip"
    # Try modern pyproject.toml first, then fallback to requirements.txt
    if [ -f "python/pyproject.toml" ]; then
      echo "Using pyproject.toml dependency groups"
      pip install -e "python[lint]"
    else
      echo "Using legacy requirements.txt"
      pip install -r python/requirements/linter_requirements.txt
    fi
  fi
}

# Function to run Python linting
run_python_lint() {
  local action="$1"  # "format" or "check"
  
  # Check if uv is available
  if command -v uv >/dev/null 2>&1; then
    pushd python
    if [[ "$action" == "format" ]]; then
      echo "Executing Python format with uv"
      uv run ruff check --fix .
    else
      echo "Executing Python format check with uv"
      uv run ruff check --no-fix .
    fi
    local result=$?
    popd
    return $result
  else
    # Use traditional approach
    if [[ "$action" == "format" ]]; then
      echo "Executing Python format"
      ruff check --fix python
    else
      echo "Executing Python format check"
      ruff check --no-fix python
    fi
    return $?
  fi
}

# Function to format files
format_files() {
  set +e
  install_python_deps
  local install_result=$?
  if [[ $install_result -ne 0 ]]; then
    echo "Failed to install Python dependencies" >&2
    return 2
  fi
  
  run_python_lint "format"
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    echo "Python format failed" >&2
    return 2
  fi
  echo "Executing Java format"
  mvn -T10 -B --no-transfer-progress spotless:apply
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    echo "Java format failed" >&2
    return 2
  fi
  echo "Executing format succeeds"
  return 0
}

# Function to check files for issues
check_files() {
  set +e
  install_python_deps
  local install_result=$?
  if [[ $install_result -ne 0 ]]; then
    echo "Failed to install Python dependencies" >&2
    return 2
  fi
  
  run_python_lint "check"
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    echo "Python format check failed" >&2
    return 2
  fi
  echo "Executing Java format check"
  mvn -T10 -B --no-transfer-progress spotless:check
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    echo "Java format check failed" >&2
    return 2
  fi
  echo "Executing format check succeeds"
  return 0
}

# Main command dispatcher
# shellcheck disable=SC2120
main() {
    local result=0
    if $format_code; then
        format_files "$@"
        result=$?
    else
        check_files "$@"
        result=$?
    fi
    if [[ $result -ne 0 ]]; then
        echo "### FORMAT CHECK FAILED ###" >&2
        return 2
    else
        echo "### FORMAT CHECK PASSED ###"
        return 0
    fi
}

# Execute main function and capture exit code
main
exit_code=$?

exit $exit_code
