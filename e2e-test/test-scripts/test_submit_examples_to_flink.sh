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
# Submits all Java/Python examples to a local Flink standalone cluster and runs
# them against a local Ollama server. Detached jobs pass after they either
# finish or remain RUNNING for a bounded stability period. Examples are
# auto-discovered from the examples directories.
#
# Env: FLINK_VERSION (default 2.3.0), FLINK_HOME (reuse existing install),
#      JOB_STARTUP_TIMEOUT (default 60), JOB_STABLE_RUNNING_SECONDS (default 20),
#      JOB_STATUS_POLL_INTERVAL (default 2), VERBOSE=1 (set -x).

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

FLINK_VERSION="${FLINK_VERSION:-2.3.0}"
FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
SUBMIT_TIMEOUT="${SUBMIT_TIMEOUT:-180}"
JOB_FINISH_TIMEOUT="${JOB_FINISH_TIMEOUT:-300}"
JOB_STARTUP_TIMEOUT="${JOB_STARTUP_TIMEOUT:-60}"
JOB_STABLE_RUNNING_SECONDS="${JOB_STABLE_RUNNING_SECONDS:-20}"
JOB_STATUS_POLL_INTERVAL="${JOB_STATUS_POLL_INTERVAL:-2}"
SLOT_RELEASE_TIMEOUT="${SLOT_RELEASE_TIMEOUT:-30}"

# Models to pull for Ollama. Override these in CI to use lighter models.
# The script aliases the pulled chat model to the names hardcoded in
# examples (e.g. qwen3:8b, qwen3.5:9b) so example code stays untouched.
OLLAMA_CHAT_MODEL="${OLLAMA_CHAT_MODEL:-qwen3:8b}"
OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"

# Model names referenced (hardcoded) by example code. The pulled chat model
# is aliased to each of these via `ollama cp` when it differs.
OLLAMA_CHAT_MODEL_ALIASES=("qwen3:1.7b" "qwen3:8b" "qwen3.5:9b")

# Bash 3 (default on macOS) lacks associative arrays.
RESULT_NAMES=()
RESULT_STATES=()
SUBMITTED_JOB_IDS=()
SUBMISSION_PIDS=()
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

        # Attached Java submissions are kept alive while their jobs are
        # checked so example shutdown hooks do not remove input files before
        # TaskManagers open them. Stop any client still left after cancelling
        # its job.
        for pid in "${SUBMISSION_PIDS[@]:-}"; do
            [[ -n "$pid" ]] || continue
            if kill -0 "$pid" 2>/dev/null; then
                kill "$pid" 2>/dev/null || true
            fi
            wait "$pid" 2>/dev/null || true
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
        log_error "$failed example(s) failed validation"
        exit 1
    fi
}

record_result() {
    RESULT_NAMES+=("$1")
    RESULT_STATES+=("$2")
}

install_flink_distribution() {
    local install_dir="$1"

    # Reuse install.sh's download, archive validation, and extraction logic,
    # but stop before it installs a released Flink Agents JAR. This E2E test
    # must run the artifacts built from the current checkout, which may support
    # a newer Flink version than the latest published Flink Agents release.
    FLINK_AGENTS_INSTALL_SH_NO_RUN=1 \
        FLINK_VERSION="$FLINK_VERSION" \
        INSTALL_FLINK=Yes \
        INSTALL_DIR="$install_dir" \
        NO_PROMPT=1 \
        bash -c '
            source "$1"
            plan_flink
            install_flink_if_needed
        ' _ "$ROOT_DIR/tools/install.sh"
}

prepare_python_venv() {
    if [[ -f "$VENV_DIR/pyvenv.cfg" && -x "$VENV_DIR/bin/python" ]]; then
        log_info "Reusing Python venv: $VENV_DIR"
        return
    fi
    if [[ -e "$VENV_DIR" ]] \
        && [[ ! -d "$VENV_DIR" \
            || -n "$(find "$VENV_DIR" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
        log_error "VENV_DIR is not an empty directory or valid venv: $VENV_DIR"
        return 1
    fi

    local python_bin="${PYTHON_BIN:-python3}"
    log_info "Creating Python venv: $VENV_DIR"
    "$python_bin" -m venv "$VENV_DIR"
}

install_flink() {
    log_section "Step 1: install Flink standalone (version $FLINK_VERSION)"

    # Anchor VENV_DIR to the repo so both fresh and reused Flink installations
    # use the same Python environment.
    export VENV_DIR="${VENV_DIR:-$ROOT_DIR/.flink-agents-env}"

    if [[ -n "${FLINK_HOME:-}" && -x "$FLINK_HOME/bin/flink" ]]; then
        log_info "Reusing existing FLINK_HOME: $FLINK_HOME"
        export FLINK_HOME
    else
        local install_dir="${INSTALL_DIR:-$HOME/.local/flink}"
        log_info "Installing the Flink distribution with tools/install.sh helpers"
        install_flink_distribution "$install_dir"
        export FLINK_HOME="${install_dir}/flink-${FLINK_VERSION}"

        if [[ ! -x "$FLINK_HOME/bin/flink" ]]; then
            log_error "Flink installation not found at expected path: $FLINK_HOME"
            return 1
        fi
        log_ok "Flink installed at: $FLINK_HOME"
    fi

    local pyflink_jar="$FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar"
    if [[ ! -f "$pyflink_jar" ]]; then
        log_error "PyFlink JAR not found in Flink distribution: $pyflink_jar"
        return 1
    fi
    cp "$pyflink_jar" "$FLINK_HOME/lib/"
    prepare_python_venv
}

install_built_python_package() {
    local wheel
    wheel=$(find "$ROOT_DIR/python/dist" -maxdepth 1 -name '*.whl' | head -n 1)
    if [[ -z "$wheel" ]]; then
        log_error "Python wheel not found after build"
        return 1
    fi

    log_info "Installing current wheel and apache-flink==$FLINK_VERSION into $VENV_DIR"
    "$VENV_DIR/bin/python" -m pip install --quiet \
        "$wheel" "apache-flink==$FLINK_VERSION"

    if ! "$VENV_DIR/bin/python" -c 'import flink_agents, pyflink' >/dev/null 2>&1; then
        log_error "Current Flink Agents wheel or PyFlink is not importable from: $VENV_DIR/bin/python"
        return 1
    fi

    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    export PYFLINK_CLIENT_EXECUTABLE="$VENV_DIR/bin/python"
    log_ok "Activated current Flink Agents and PyFlink venv: $VENV_DIR"
}

build_project() {
    log_section "Step 2: build flink-agents (Java + Python)"
    (
        cd "$ROOT_DIR"
        SKIP_SPOTLESS_CHECK=true bash tools/build.sh
    )
    install_built_python_package
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

# Starts a Java example in attached mode but leaves the Flink client in the
# background. Keeping the client alive preserves temporary resources created
# by the example until startup validation and cancellation have completed.
ATTACHED_SUBMISSION_PID=""
ATTACHED_SUBMISSION_JOB_ID=""
remove_submission_pid() {
    local target_pid="$1"
    local remaining_pids=()
    local pid

    for pid in "${SUBMISSION_PIDS[@]:-}"; do
        if [[ -n "$pid" && "$pid" != "$target_pid" ]]; then
            remaining_pids+=("$pid")
        fi
    done
    # Rebuild with the `+` form: on bash 4.3 and older "${arr[@]}" on an empty
    # array is an unbound-variable error under `set -u`, and "${arr[@]:-}" would
    # rebuild one empty-string element instead of nothing. The `:-` in the loop
    # above is correct: its empty iteration is dropped by the `-n` guard.
    SUBMISSION_PIDS=("${remaining_pids[@]+"${remaining_pids[@]}"}")
}

start_attached_java_submission() {
    local class_name="$1" out="$2"
    local elapsed=0

    ATTACHED_SUBMISSION_PID=""
    ATTACHED_SUBMISSION_JOB_ID=""

    timeout "$SUBMIT_TIMEOUT" "$FLINK_HOME/bin/flink" run \
            -c "$class_name" \
            "$EXAMPLES_JAR" >"$out" 2>&1 &
    ATTACHED_SUBMISSION_PID=$!
    SUBMISSION_PIDS+=("$ATTACHED_SUBMISSION_PID")

    while (( elapsed < SUBMIT_TIMEOUT )); do
        ATTACHED_SUBMISSION_JOB_ID=$(extract_job_id "$out") || true
        if [[ -n "$ATTACHED_SUBMISSION_JOB_ID" ]]; then
            return 0
        fi

        if ! kill -0 "$ATTACHED_SUBMISSION_PID" 2>/dev/null; then
            wait "$ATTACHED_SUBMISSION_PID" 2>/dev/null || true
            remove_submission_pid "$ATTACHED_SUBMISSION_PID"
            return 1
        fi

        sleep 1
        elapsed=$((elapsed + 1))
    done

    log_error "Timed out waiting for Java example job id"
    kill "$ATTACHED_SUBMISSION_PID" 2>/dev/null || true
    wait "$ATTACHED_SUBMISSION_PID" 2>/dev/null || true
    remove_submission_pid "$ATTACHED_SUBMISSION_PID"
    return 1
}

wait_for_attached_submission_client() {
    local pid="$1" name="$2"
    local elapsed=0

    while kill -0 "$pid" 2>/dev/null && (( elapsed < 30 )); do
        sleep 1
        elapsed=$((elapsed + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        log_warn "$name: attached Flink client did not exit after cleanup; stopping it"
        kill "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
    remove_submission_pid "$pid"
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

extract_job_state() {
    printf '%s\n' "$1" \
        | grep -Eo '"state"[[:space:]]*:[[:space:]]*"[^"]+"' \
        | sed -n '1{s/.*"\([^"]*\)"$/\1/p;}'
}

# Sets JOB_STATE to the current state returned by Flink's REST API.
JOB_STATE=""
get_job_state() {
    local job_id="$1"
    local response
    JOB_STATE=""

    response=$(curl -fsS "http://localhost:8081/jobs/$job_id" 2>/dev/null) || return 1
    JOB_STATE=$(extract_job_state "$response") || return 1
    [[ -n "$JOB_STATE" ]]
}

extract_available_slots() {
    printf '%s\n' "$1" \
        | grep -Eo '"slots-available"[[:space:]]*:[[:space:]]*[0-9]+' \
        | sed -n '1{s/.*:[[:space:]]*//p;}'
}

# Sets AVAILABLE_SLOTS to the number reported by Flink's overview endpoint.
AVAILABLE_SLOTS=""
get_available_slots() {
    local response
    AVAILABLE_SLOTS=""

    response=$(curl -fsS "http://localhost:8081/overview" 2>/dev/null) || return 1
    AVAILABLE_SLOTS=$(extract_available_slots "$response") || return 1
    [[ -n "$AVAILABLE_SLOTS" ]]
}

wait_for_cluster_slot_available() {
    local name="$1" timeout_sec="${2:-$SLOT_RELEASE_TIMEOUT}"
    local elapsed=0

    while (( elapsed < timeout_sec )); do
        if get_available_slots && (( AVAILABLE_SLOTS > 0 )); then
            return 0
        fi
        sleep "$JOB_STATUS_POLL_INTERVAL"
        elapsed=$((elapsed + JOB_STATUS_POLL_INTERVAL))
    done

    log_warn "$name: cluster slot was not released within ${timeout_sec}s"
    return 1
}

# Python operators may take substantially longer than the bounded health check
# to tear down after cancellation. Restart the single-node standalone cluster
# instead of letting one slow cleanup starve every subsequently submitted job.
restart_cluster_after_cleanup() {
    local name="$1"

    log_warn "$name: restarting the standalone cluster to recover its slot"
    if ! "$FLINK_HOME/bin/stop-cluster.sh" >/dev/null 2>&1; then
        log_warn "$name: stop-cluster.sh reported an error; attempting startup anyway"
    fi
    if ! "$FLINK_HOME/bin/start-cluster.sh" >/dev/null 2>&1; then
        log_error "$name: failed to restart the standalone cluster"
        return 1
    fi
    if ! wait_for_cluster_slot_available "$name after cluster restart" 60; then
        log_error "$name: cluster restart did not recover an available slot"
        return 1
    fi

    log_ok "$name: standalone cluster restarted and slot recovered"
}

ensure_cluster_slot_available() {
    local name="$1"

    if wait_for_cluster_slot_available "$name"; then
        return 0
    fi
    restart_cluster_after_cleanup "$name"
}

# A detached example is considered healthy when it finishes successfully or
# remains continuously RUNNING for a short period. This catches startup
# failures without waiting for slow inference or unbounded sources to finish.
wait_for_job_healthy() {
    local job_id="$1" name="$2" timeout_sec="${3:-$JOB_STARTUP_TIMEOUT}"
    local stable_sec="${4:-$JOB_STABLE_RUNNING_SECONDS}"
    local elapsed=0
    local running_for=0
    local previous_state=""

    while (( elapsed < timeout_sec )); do
        local status
        if get_job_state "$job_id"; then
            status="$JOB_STATE"
        else
            status="UNAVAILABLE"
        fi

        if [[ "$status" != "$previous_state" ]]; then
            log_info "$name state: $status"
        fi

        case "$status" in
            FINISHED)
                log_ok "$name reached FINISHED status"
                return 0
                ;;
            RUNNING)
                if [[ "$previous_state" == "RUNNING" ]]; then
                    running_for=$((running_for + JOB_STATUS_POLL_INTERVAL))
                else
                    running_for=0
                fi
                if (( running_for >= stable_sec )); then
                    log_ok "$name remained RUNNING for ${stable_sec}s"
                    return 0
                fi
                ;;
            FAILING|FAILED|CANCELLING|CANCELED|SUSPENDED)
                log_error "$name entered unexpected state: $status"
                check_logs_for_errors "$name" || true
                return 1
                ;;
            *)
                running_for=0
                ;;
        esac

        previous_state="$status"
        sleep "$JOB_STATUS_POLL_INTERVAL"
        elapsed=$((elapsed + JOB_STATUS_POLL_INTERVAL))
    done

    log_error "$name did not become stably RUNNING or FINISHED within ${timeout_sec}s"
    return 1
}

cancel_job_after_check() {
    local job_id="$1" name="$2"

    if get_job_state "$job_id"; then
        case "$JOB_STATE" in
            FINISHED|FAILED|CANCELED|SUSPENDED)
                ensure_cluster_slot_available "$name"
                return $?
                ;;
        esac
    fi

    log_info "$name: cancelling job after startup verification"
    if timeout 30 "$FLINK_HOME/bin/flink" cancel "$job_id" >/dev/null 2>&1; then
        if ensure_cluster_slot_available "$name"; then
            log_ok "$name cancelled and cluster slot released"
            return 0
        fi
        return 1
    fi

    # The job may have reached a terminal state while the cancel request raced
    # with completion. Treat that as successful cleanup.
    if get_job_state "$job_id"; then
        case "$JOB_STATE" in
            FINISHED|FAILED|CANCELED|SUSPENDED)
                ensure_cluster_slot_available "$name"
                return $?
                ;;
        esac
    fi

    # A fast FINISHED job can disappear from the active-job endpoint before
    # the failed cancel request returns. In this sequential single-slot test,
    # an available slot is sufficient evidence that cleanup has completed.
    if ensure_cluster_slot_available "$name"; then
        log_ok "$name no longer occupies the cluster slot"
        return 0
    fi

    log_error "$name: failed to cancel job $job_id"
    return 1
}

verify_submitted_job() {
    local job_id="$1" name="$2"
    local healthy=0
    local cleaned_up=0

    if wait_for_job_healthy "$job_id" "$name"; then
        healthy=1
    fi
    if cancel_job_after_check "$job_id" "$name"; then
        cleaned_up=1
    fi

    if (( healthy == 1 && cleaned_up == 1 )); then
        record_result "$name" "PASS"
    else
        record_result "$name" "FAIL"
    fi
}

submit_java_example() {
    local class_name="$1"
    local label="java:${class_name##*.}"
    log_section "Submitting Java example: $class_name"

    local out
    out=$(mktemp)
    local rc=0
    start_attached_java_submission "$class_name" "$out" || rc=$?
    cat "$out"

    if (( rc != 0 )); then
        log_error "$label submission failed (exit $rc)"
        record_result "$label" "FAIL"
        rm -f "$out"
        return 0
    fi

    local job_id="$ATTACHED_SUBMISSION_JOB_ID"
    if [[ -z "$job_id" ]]; then
        log_error "$label: could not extract job id"
        record_result "$label" "FAIL"
        rm -f "$out"
        return 0
    fi
    SUBMITTED_JOB_IDS+=("$job_id")

    log_ok "$label submitted successfully (JobID: $job_id)"
    verify_submitted_job "$job_id" "$label"
    wait_for_attached_submission_client "$ATTACHED_SUBMISSION_PID" "$label"
    rm -f "$out"
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

    log_ok "$label submitted successfully (JobID: $job_id)"
    verify_submitted_job "$job_id" "$label"
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
    local jar_entries
    if ! jar_entries=$(jar -tf "$EXAMPLES_JAR"); then
        log_error "Failed to inspect examples JAR: $EXAMPLES_JAR"
        return 1
    fi

    local discovered_classes
    discovered_classes=$(printf '%s\n' "$jar_entries" \
        | grep '^org/apache/flink/agents/examples/[^/]*Example\.class$' \
        | sed 's|/|.|g; s|\.class$||' || true)

    local classes=()
    if [[ -n "$discovered_classes" ]]; then
        while IFS= read -r class; do
            classes+=("$class")
        done <<< "$discovered_classes"
    fi

    if [[ ${#classes[@]} -eq 0 ]]; then
        log_error "No Java example classes found in $EXAMPLES_JAR"
        return 1
    fi
    log_info "Discovered ${#classes[@]} Java example(s): ${classes[*]}"
    printf '%s\n' "${classes[@]}"
}

discover_python_quickstart_examples() {
    local dir="$ROOT_DIR/python/flink_agents/examples/quickstart"
    if [[ ! -d "$dir" ]]; then
        log_error "Python quickstart examples directory not found: $dir"
        return 1
    fi

    local discovered_scripts
    if ! discovered_scripts=$(
        find "$dir" -maxdepth 1 -name '*_example.py' -type f | sort
    ); then
        log_error "Failed to discover Python quickstart examples in $dir"
        return 1
    fi

    local scripts=()
    if [[ -n "$discovered_scripts" ]]; then
        while IFS= read -r f; do
            scripts+=("$f")
        done <<< "$discovered_scripts"
    fi

    if [[ ${#scripts[@]} -eq 0 ]]; then
        log_error "No Python quickstart examples found in $dir"
        return 1
    fi
    log_info "Discovered ${#scripts[@]} Python quickstart example(s)"
    printf '%s\n' "${scripts[@]}"
}

discover_python_rag_examples() {
    local dir="$ROOT_DIR/python/flink_agents/examples/rag"
    if [[ ! -d "$dir" ]]; then
        log_info "No RAG examples directory found, skipping"
        return 0
    fi

    local discovered_scripts
    if ! discovered_scripts=$(
        find "$dir" -maxdepth 1 -name '*_example.py' -type f | sort
    ); then
        log_error "Failed to discover Python RAG examples in $dir"
        return 1
    fi

    local scripts=()
    if [[ -n "$discovered_scripts" ]]; then
        while IFS= read -r f; do
            scripts+=("$f")
        done <<< "$discovered_scripts"
    fi

    if [[ ${#scripts[@]} -eq 0 ]]; then
        log_info "No RAG examples found in $dir"
        return 0
    fi
    log_info "Discovered ${#scripts[@]} Python RAG example(s)"
    printf '%s\n' "${scripts[@]}"
}

setup_rag_knowledge_base() {
    local setup_script="$ROOT_DIR/python/flink_agents/examples/rag/knowledge_base_setup.py"
    if [[ ! -f "$setup_script" ]]; then
        log_error "RAG knowledge_base_setup.py not found: $setup_script"
        return 1
    fi
    log_info "Setting up RAG knowledge base"
    if ! python "$setup_script"; then
        log_error "RAG knowledge base setup failed"
        return 1
    fi
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
    local java_examples
    if ! java_examples=$(discover_java_examples); then
        log_error "Java example discovery failed"
        return 1
    fi
    if [[ -z "$java_examples" ]]; then
        log_error "Java example discovery returned no examples"
        return 1
    fi
    while IFS= read -r class; do
        submit_java_example "$class"
    done <<< "$java_examples"

    # Auto-discover and submit Python quickstart examples
    log_section "Step 8: submit Python quickstart examples"
    local python_quickstart_examples
    if ! python_quickstart_examples=$(discover_python_quickstart_examples); then
        log_error "Python quickstart example discovery failed"
        return 1
    fi
    if [[ -z "$python_quickstart_examples" ]]; then
        log_error "Python quickstart example discovery returned no examples"
        return 1
    fi
    while IFS= read -r script; do
        submit_python_example "$script"
    done <<< "$python_quickstart_examples"

    # Auto-discover and run Python RAG examples (these run end-to-end, not as detached jobs)
    log_section "Step 9: run Python RAG examples"
    local python_rag_examples
    if ! python_rag_examples=$(discover_python_rag_examples); then
        log_error "Python RAG example discovery failed"
        return 1
    fi
    if [[ -n "$python_rag_examples" ]]; then
        if ! setup_rag_knowledge_base; then
            log_error "Cannot run Python RAG examples because setup failed"
            return 1
        fi
        while IFS= read -r script; do
            submit_python_rag_example "$script"
        done <<< "$python_rag_examples"
    fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    trap cleanup EXIT
    main "$@"
fi
