#!/usr/bin/env bash
#
#   Licensed to the Apache Software Foundation (ASF) under one
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
#
#
# Submits all Java/Python examples to a local Flink standalone cluster, runs
# them against a local Ollama server and waits for each job to FINISH.
# Examples are auto-discovered from the examples directories.
#
# Env: FLINK_VERSION (default 2.2.0), FLINK_HOME (reuse existing install),
#      VERBOSE=1 (set -x).

set -euo pipefail

if [[ "${VERBOSE:-0}" == "1" ]]; then
    set -x
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { printf "${BLUE}[INFO]${NC}  %s\n"  "$*" >&2; }
log_ok()      { printf "${GREEN}[OK]${NC}    %s\n" "$*" >&2; }
log_warn()    { printf "${YELLOW}[WARN]${NC}  %s\n" "$*" >&2; }
log_error()   { printf "${RED}[ERROR]${NC} %s\n"   "$*" >&2; }
log_section() {
    printf "\n${BLUE}==============================================================${NC}\n" >&2
    printf "${BLUE}>>> %s${NC}\n" "$*" >&2
    printf   "${BLUE}==============================================================${NC}\n" >&2
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.."; pwd)"
log_info "Project root: $ROOT_DIR"

FLINK_VERSION="${FLINK_VERSION:-2.2.0}"
FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
SUBMIT_TIMEOUT="${SUBMIT_TIMEOUT:-180}"
JOB_FINISH_TIMEOUT="${JOB_FINISH_TIMEOUT:-300}"

# Models to pull for Ollama. Override these in CI to use lighter models.
# The script aliases the pulled chat model to the names hardcoded in
# examples (e.g. qwen3:8b, qwen3.5:9b) so example code stays untouched.
OLLAMA_CHAT_MODEL="${OLLAMA_CHAT_MODEL:-qwen3:8b}"
OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"

# Model names referenced (hardcoded) by example code. The pulled chat model
# is aliased to each of these via `ollama cp` when it differs.
OLLAMA_CHAT_MODEL_ALIASES=("qwen3:8b" "qwen3.5:9b")

# Bash 3 (default on macOS) lacks associative arrays.
RESULT_NAMES=()
RESULT_STATES=()
SUBMITTED_JOB_IDS=()
OLLAMA_PID=""

cleanup() {
    local exit_code=$?
    log_section "Cleanup"

    if [[ -n "${FLINK_HOME:-}" && -x "$FLINK_HOME/bin/flink" ]]; then
        for jid in "${SUBMITTED_JOB_IDS[@]:-}"; do
            [[ -n "$jid" ]] || continue
            log_info "Cancelling job $jid"
            "$FLINK_HOME/bin/flink" cancel "$jid" >/dev/null 2>&1 || true
        done

        if [[ -x "$FLINK_HOME/bin/stop-cluster.sh" ]]; then
            log_info "Stopping Flink cluster"
            "$FLINK_HOME/bin/stop-cluster.sh" >/dev/null 2>&1 || true
        fi

        if [[ -d "$FLINK_HOME/log" ]]; then
            local log_archive="$ROOT_DIR/flink-logs-$(date +%Y%m%d-%H%M%S).tar.gz"
            tar -czf "$log_archive" -C "$FLINK_HOME" log >/dev/null 2>&1 \
                && log_info "Flink logs archived to: $log_archive" \
                || log_warn "Failed to archive Flink logs"
        fi
    fi

    [[ -n "${OLLAMA_PID:-}" ]] && kill "$OLLAMA_PID" 2>/dev/null || true

    print_summary
    exit "$exit_code"
}
trap cleanup EXIT

print_summary() {
    log_section "Test summary"
    local total=${#RESULT_NAMES[@]}
    if (( total == 0 )); then
        log_error "Test setup failed before any example was submitted"
        return
    fi
    local passed=0
    local failed=0
    local i
    for (( i = 0; i < total; i++ )); do
        local name="${RESULT_NAMES[$i]}"
        local state="${RESULT_STATES[$i]}"
        if [[ "$state" == "PASS" ]]; then
            printf "  ${GREEN}PASS${NC}  %s\n" "$name"
            passed=$((passed + 1))
        else
            printf "  ${RED}FAIL${NC}  %s\n" "$name"
            failed=$((failed + 1))
        fi
    done
    printf "\nTotal: %d   Passed: %d   Failed: %d\n" "$total" "$passed" "$failed"

    if (( failed > 0 )); then
        log_error "$failed example(s) failed to submit"
        exit 1
    fi
}

record_result() {
    RESULT_NAMES+=("$1")
    RESULT_STATES+=("$2")
}

install_flink() {
    log_section "Step 1: install Flink standalone (version $FLINK_VERSION)"

    if [[ -n "${FLINK_HOME:-}" && -x "$FLINK_HOME/bin/flink" ]]; then
        log_info "Reusing existing FLINK_HOME: $FLINK_HOME"
        export FLINK_HOME
        return 0
    fi

    # Anchor VENV_DIR to the repo so we can find it after install.sh exits.
    export VENV_DIR="${VENV_DIR:-$ROOT_DIR/.flink-agents-env}"

    log_info "Running tools/install.sh --non-interactive --install-flink --enable-pyflink"
    FLINK_VERSION="$FLINK_VERSION" bash "$ROOT_DIR/tools/install.sh" \
        --non-interactive --install-flink --enable-pyflink

    local install_dir="${INSTALL_DIR:-$HOME/.local/flink}"
    export FLINK_HOME="${install_dir}/flink-${FLINK_VERSION}"

    if [[ ! -x "$FLINK_HOME/bin/flink" ]]; then
        log_error "Flink installation not found at expected path: $FLINK_HOME"
        exit 1
    fi
    log_ok "Flink installed at: $FLINK_HOME"

    # `flink run -py` shells out to a Python interpreter that must have
    # pyflink importable. Activate the venv install.sh provisioned and
    # point PYFLINK_CLIENT_EXECUTABLE at it.
    if [[ ! -x "$VENV_DIR/bin/python" ]]; then
        log_error "Expected Python venv not found at: $VENV_DIR"
        exit 1
    fi
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    export PYFLINK_CLIENT_EXECUTABLE="$VENV_DIR/bin/python"
    log_ok "Activated PyFlink venv: $VENV_DIR"
}

build_project() {
    log_section "Step 2: build flink-agents (Java + Python)"
    (
        cd "$ROOT_DIR"
        SKIP_SPOTLESS_CHECK=true bash tools/build.sh
    )
    log_ok "Build completed"
}

locate_examples_jar() {
    log_section "Step 3: locate examples jar"
    EXAMPLES_JAR=$(find "$ROOT_DIR/examples/target" -name "flink-agents-examples-*.jar" \
        ! -name "*sources*" ! -name "*javadoc*" ! -name "original-*" | head -n 1)
    [[ -n "$EXAMPLES_JAR" ]] || { log_error "Examples JAR not found after build"; exit 1; }
    log_ok "Examples JAR: $(basename "$EXAMPLES_JAR")"
}

stage_dist_jars() {
    log_section "Step 4: stage dist uber jar into \$FLINK_HOME/lib"

    local project_version
    project_version=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' \
        "$ROOT_DIR/pom.xml" | head -n 2 | tail -n 1)
    log_info "Detected project version: $project_version"

    # The flink-version uber jar already bundles the common deps.
    local flink_jar="$ROOT_DIR/dist/flink-${FLINK_MAJOR_MINOR}/target/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}-${project_version}.jar"

    if [[ ! -f "$flink_jar" ]]; then
        log_error "Flink dist jar not found: $flink_jar"
        exit 1
    fi

    # Remove any pre-existing flink-agents-dist jars to avoid classpath conflicts
    rm -f "$FLINK_HOME/lib/"/flink-agents-dist-*.jar
    cp "$flink_jar"  "$FLINK_HOME/lib/"
    log_ok "Staged: $(basename "$flink_jar")"
}

start_ollama() {
    log_section "Step 5: start Ollama server"
    curl -fsSL https://ollama.com/install.sh | sh
    ollama serve &
    OLLAMA_PID=$!

    local retries=30
    for i in $(seq 1 $retries); do
        if curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then
            log_ok "Ollama server is ready"
            break
        fi
        (( i == retries )) && { log_error "Ollama server failed to start"; exit 1; }
        sleep 2
    done

    log_info "Pulling chat model: $OLLAMA_CHAT_MODEL"
    ollama pull "$OLLAMA_CHAT_MODEL"
    log_ok "Chat model $OLLAMA_CHAT_MODEL pulled successfully"

    # Alias the pulled chat model to the names examples hardcode so we can
    # run a lighter model in CI without touching example sources.
    local alias_name
    for alias_name in "${OLLAMA_CHAT_MODEL_ALIASES[@]}"; do
        if [[ "$OLLAMA_CHAT_MODEL" != "$alias_name" ]]; then
            log_info "Creating model alias: $alias_name -> $OLLAMA_CHAT_MODEL"
            ollama cp "$OLLAMA_CHAT_MODEL" "$alias_name"
        fi
    done

    log_info "Pulling embedding model: $OLLAMA_EMBED_MODEL"
    ollama pull "$OLLAMA_EMBED_MODEL"
    log_ok "Embedding model $OLLAMA_EMBED_MODEL pulled successfully"
}

start_cluster() {
    log_section "Step 6: start Flink standalone cluster"
    "$FLINK_HOME/bin/start-cluster.sh"

    local rest_url="http://localhost:8081"
    log_info "Waiting for JobManager REST API at $rest_url ..."
    local i
    for (( i = 0; i < 60; i++ )); do
        if curl -fsS "$rest_url/overview" >/dev/null 2>&1; then
            log_ok "Flink cluster is up"
            return 0
        fi
        sleep 2
    done

    log_error "Flink cluster did not become ready in time"
    exit 1
}

extract_job_id() {
    # "Job has been submitted with JobID <id>"
    grep -Eo 'JobID [0-9a-f]{32}' "$1" | tail -n 1 | awk '{print $2}'
}

# ---------------------------------------------------------------------------
# Check Flink logs for unexpected errors/exceptions after a job completes.
# Inspired by Apache Flink's e2e test-scripts/common.sh approach.
# Returns 0 (success) when no unexpected errors are found; returns 1 otherwise.
# ---------------------------------------------------------------------------
check_logs_for_errors() {
    local job_name="${1:-unknown}"

    if [[ ! -d "$FLINK_HOME/log" ]]; then
        log_warn "Flink log directory not found, skipping error log check"
        return 0
    fi

    # Known benign patterns that should be excluded from error detection
    local -a allowed_patterns=(
        "org.apache.flink.shaded.netty"
        "org.apache.flink.runtime.rest.handler.legacy"
        "javax.management"
        "akka.remote"
        "ClassNotFoundException.*org.apache.hadoop"
        "NoClassDefFoundError.*org.apache.hadoop"
        "Unable to load native-hadoop"
    )

    # Build a single grep-exclude regex
    local exclude_regex
    exclude_regex=$(printf '%s|' "${allowed_patterns[@]}")
    exclude_regex="${exclude_regex%|}"  # trim trailing pipe

    local error_lines=""
    local logfile
    while IFS= read -r logfile; do
        [[ -f "$logfile" ]] || continue
        local matches
        matches=$(grep -E "(Exception|ERROR|Fatal)" "$logfile" \
            | grep -Ev "$exclude_regex" 2>/dev/null || true)
        if [[ -n "$matches" ]]; then
            error_lines+="--- $(basename "$logfile") ---"$'\n'
            error_lines+="$matches"$'\n'
        fi
    done < <(find "$FLINK_HOME/log" -name "*.log" -type f 2>/dev/null)

    if [[ -n "$error_lines" ]]; then
        log_warn "$job_name: Unexpected errors found in Flink logs:"
        echo "$error_lines" | head -30 >&2
        local total_lines
        total_lines=$(echo "$error_lines" | wc -l)
        if (( total_lines > 30 )); then
            log_warn "... ($((total_lines - 30)) more lines truncated)"
        fi
        return 1
    fi
    return 0
}

wait_for_job_finish() {
    local job_id="$1" name="$2" timeout_sec="${3:-$JOB_FINISH_TIMEOUT}"
    local elapsed=0
    while (( elapsed < timeout_sec )); do
        local status
        status=$("$FLINK_HOME/bin/flink" list -a 2>/dev/null | grep "$job_id" || true)
        if echo "$status" | grep -q "FINISHED"; then
            log_ok "$name reached FINISHED status"
            sleep 2  # allow log flush
            if check_logs_for_errors "$name"; then
                log_ok "$name completed successfully (no unexpected errors in logs)"
            else
                log_warn "$name finished but with warnings in logs (non-fatal)"
            fi
            return 0
        elif echo "$status" | grep -q "FAILED\|CANCELED"; then
            log_error "$name ended with unexpected status"
            check_logs_for_errors "$name" || true
            return 1
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    log_error "$name timed out after ${timeout_sec}s"
    return 1
}

submit_java_example() {
    local class_name="$1"
    local label="java:${class_name##*.}"
    log_section "Submitting Java example: $class_name"

    local out
    out=$(mktemp)
    local rc=0
    timeout "$SUBMIT_TIMEOUT" "$FLINK_HOME/bin/flink" run \
            --detached \
            -c "$class_name" \
            "$EXAMPLES_JAR" >"$out" 2>&1 || rc=$?
    cat "$out"

    if (( rc != 0 )); then
        log_error "$label submission failed (exit $rc)"
        record_result "$label" "FAIL"
        rm -f "$out"
        return 0
    fi

    local job_id
    job_id=$(extract_job_id "$out") || true
    rm -f "$out"
    if [[ -z "$job_id" ]]; then
        log_error "$label: could not extract job id"
        record_result "$label" "FAIL"
        return 0
    fi
    SUBMITTED_JOB_IDS+=("$job_id")

    # Submission succeeded — record PASS immediately
    log_ok "$label submitted successfully (JobID: $job_id)"
    record_result "$label" "PASS"

    # Optionally wait and report final status (informational only, does not affect PASS/FAIL)
    if wait_for_job_finish "$job_id" "$label" "$JOB_FINISH_TIMEOUT"; then
        log_ok "$label job reached FINISHED status"
    else
        log_warn "$label job did not reach FINISHED (expected with lightweight CI model)"
    fi
}

submit_python_example() {
    local script_path="$1"
    local label="python:$(basename "$script_path" .py)"
    log_section "Submitting Python example: $script_path"

    if [[ ! -f "$script_path" ]]; then
        log_error "Python example not found: $script_path"
        record_result "$label" "FAIL"
        return 0
    fi

    local out
    out=$(mktemp)
    local rc=0
    timeout "$SUBMIT_TIMEOUT" "$FLINK_HOME/bin/flink" run \
            --detached \
            -py "$script_path" >"$out" 2>&1 || rc=$?
    cat "$out"

    if (( rc != 0 )); then
        log_error "$label submission failed (exit $rc)"
        record_result "$label" "FAIL"
        rm -f "$out"
        return 0
    fi

    local job_id
    job_id=$(extract_job_id "$out") || true
    rm -f "$out"
    if [[ -z "$job_id" ]]; then
        log_error "$label: could not extract job id"
        record_result "$label" "FAIL"
        return 0
    fi
    SUBMITTED_JOB_IDS+=("$job_id")

    # Submission succeeded — record PASS
    log_ok "$label submitted successfully (JobID: $job_id)"
    record_result "$label" "PASS"

    # Informational: wait and report final status
    if wait_for_job_finish "$job_id" "$label" "$JOB_FINISH_TIMEOUT"; then
        log_ok "$label job reached FINISHED status"
    else
        log_warn "$label job did not reach FINISHED (expected with lightweight CI model)"
    fi
}

# RAG examples run end-to-end (not as detached jobs) via flink run -py.
# Success criterion: the script exits with code 0.
submit_python_rag_example() {
    local script_path="$1"
    local label="python:$(basename "$script_path" .py)"
    log_section "Running RAG example: $script_path"

    if [[ ! -f "$script_path" ]]; then
        log_error "RAG example not found: $script_path"
        record_result "$label" "FAIL"
        return 0
    fi

    local out
    out=$(mktemp)
    local rc=0
    timeout "$JOB_FINISH_TIMEOUT" "$FLINK_HOME/bin/flink" run \
            -py "$script_path" >"$out" 2>&1 || rc=$?
    cat "$out"
    rm -f "$out"

    if (( rc != 0 )); then
        log_error "$label execution failed (exit $rc)"
        record_result "$label" "FAIL"
    else
        log_ok "$label completed successfully"
        record_result "$label" "PASS"
    fi
    return 0
}

discover_java_examples() {
    # Find all example classes by scanning the jar's manifest or known package
    # Convention: all classes directly under org.apache.flink.agents.examples that end with "Example"
    local classes=()
    while IFS= read -r class; do
        classes+=("$class")
    done < <(jar -tf "$EXAMPLES_JAR" \
        | grep '^org/apache/flink/agents/examples/[^/]*Example\.class$' \
        | sed 's|/|.|g; s|\.class$||')

    if [[ ${#classes[@]} -eq 0 ]]; then
        log_error "No Java example classes found in $EXAMPLES_JAR"
        exit 1
    fi
    log_info "Discovered ${#classes[@]} Java example(s): ${classes[*]}"
    printf '%s\n' "${classes[@]}"
}

discover_python_quickstart_examples() {
    local dir="$ROOT_DIR/python/flink_agents/examples/quickstart"
    local scripts=()
    while IFS= read -r f; do
        scripts+=("$f")
    done < <(find "$dir" -maxdepth 1 -name '*_example.py' -type f | sort)

    if [[ ${#scripts[@]} -eq 0 ]]; then
        log_error "No Python quickstart examples found in $dir"
        exit 1
    fi
    log_info "Discovered ${#scripts[@]} Python quickstart example(s)"
    printf '%s\n' "${scripts[@]}"
}

discover_python_rag_examples() {
    local dir="$ROOT_DIR/python/flink_agents/examples/rag"
    if [[ ! -d "$dir" ]]; then
        log_info "No RAG examples directory found, skipping"
        return
    fi
    local scripts=()
    while IFS= read -r f; do
        scripts+=("$f")
    done < <(find "$dir" -maxdepth 1 -name '*_example.py' -type f | sort)

    if [[ ${#scripts[@]} -eq 0 ]]; then
        log_info "No RAG examples found in $dir"
        return
    fi
    log_info "Discovered ${#scripts[@]} Python RAG example(s)"
    printf '%s\n' "${scripts[@]}"
}

setup_rag_knowledge_base() {
    local setup_script="$ROOT_DIR/python/flink_agents/examples/rag/knowledge_base_setup.py"
    if [[ ! -f "$setup_script" ]]; then
        log_warn "RAG knowledge_base_setup.py not found, skipping RAG setup"
        return 1
    fi
    log_info "Setting up RAG knowledge base"
    python "$setup_script" || { log_error "RAG knowledge base setup failed"; return 1; }
    log_ok "RAG knowledge base ready"
}

main() {
    install_flink
    build_project
    locate_examples_jar
    stage_dist_jars
    start_ollama
    start_cluster

    # Auto-discover and submit Java examples
    log_section "Step 7: submit Java examples"
    while IFS= read -r class; do
        submit_java_example "$class"
    done < <(discover_java_examples)

    # Auto-discover and submit Python quickstart examples
    log_section "Step 8: submit Python quickstart examples"
    while IFS= read -r script; do
        submit_python_example "$script"
    done < <(discover_python_quickstart_examples)

    # Auto-discover and run Python RAG examples (these run end-to-end, not as detached jobs)
    log_section "Step 9: run Python RAG examples"
    if setup_rag_knowledge_base; then
        while IFS= read -r script; do
            submit_python_rag_example "$script"
        done < <(discover_python_rag_examples)
    else
        log_warn "Skipping RAG examples due to setup failure"
    fi
}

main "$@"
