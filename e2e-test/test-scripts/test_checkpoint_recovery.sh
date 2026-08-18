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
# Verifies that Python agent memory survives the loss of a TaskManager process.
#
# Submits checkpoint_recovery_job.py to a local Flink standalone cluster. The job
# parks itself inside a tool call that blocks on a file this script creates, so a
# completed checkpoint provably holds the agent's memory before anything is killed.
# The script then hard-kills the TaskManager, restarts it, waits for a real restore,
# releases the tool and reads the verdict the job publishes.
#
# Unlike its sibling test_submit_examples_to_flink.sh, a successful submission is
# NOT a pass: the only pass is a verdict file that says so.
#
# Env: FLINK_VERSION (default 2.3.0), FLINK_HOME (reuse existing install),
#      VERBOSE=1 (set -x), plus the *_TIMEOUT overrides below.
#
# A FLINK_HOME passed in has to be that same FLINK_VERSION. The run copies
# opt/flink-python-<FLINK_VERSION>.jar into its lib/ and stops when that file is
# not there, so point FLINK_VERSION at whatever installation FLINK_HOME names.

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
REST_URL="${REST_URL:-http://localhost:8081}"

JOB_MODULE="flink_agents/e2e_tests/e2e_tests_integration/checkpoint_recovery_job.py"
EXPECTED_AGENTS_VERSION="${EXPECTED_AGENTS_VERSION:-0.3.dev0}"

# Checkpoint interval is deliberately short: the run is parked while we wait for two
# checkpoints to complete, and that wait is charged against the tool's own deadline.
# Held in milliseconds because that is the unit the checkpoint-config endpoint reports,
# so the value written and the value asserted are the same number.
CHECKPOINT_INTERVAL_MS="${CHECKPOINT_INTERVAL_MS:-5000}"
RESTART_ATTEMPTS="${RESTART_ATTEMPTS:-3}"
# Unset, this falls back to slot.request.timeout (5 min), after which a pending slot
# request is failed and one restart attempt is burned while the TM is being replaced.
STANDALONE_STARTUP_TIME="${STANDALONE_STARTUP_TIME:-600s}"

# Budgets. Setup waits are unconstrained; the four marked ones run after the tool has
# parked and are therefore charged against the tool's own release deadline. They are
# clamped at runtime to the time actually remaining (see charged_timeout), so these
# numbers are ceilings rather than guarantees.
CLUSTER_TIMEOUT="${CLUSTER_TIMEOUT:-120}"
SUBMIT_TIMEOUT="${SUBMIT_TIMEOUT:-300}"
JOB_RUNNING_TIMEOUT="${JOB_RUNNING_TIMEOUT:-180}"
IDENTITY_TIMEOUT="${IDENTITY_TIMEOUT:-180}"
TOOL_ENTERED_TIMEOUT="${TOOL_ENTERED_TIMEOUT:-300}"
CHECKPOINT_TIMEOUT="${CHECKPOINT_TIMEOUT:-30}"    # charged against the tool deadline
TM_GONE_TIMEOUT="${TM_GONE_TIMEOUT:-45}"          # charged against the tool deadline
TM_UP_TIMEOUT="${TM_UP_TIMEOUT:-30}"              # charged against the tool deadline
RESTORE_TIMEOUT="${RESTORE_TIMEOUT:-30}"          # charged against the tool deadline
VERDICT_TIMEOUT="${VERDICT_TIMEOUT:-120}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"

# Every REST call is bounded. This is charged in the budget arithmetic, so keep the
# two in step: a healthy local JobManager answers in milliseconds, and a large value
# here buys nothing while inflating the worst-case overrun of every wait.
CURL_MAX_TIME="${CURL_MAX_TIME:-5}"

# Wall-clock costs inside the charged window that belong to no single wait: the
# individual REST reads between the waits, two jps invocations, the SIGKILL grace and
# starting the replacement TaskManager. This is an estimate, and deliberately only
# feeds the pre-flight feasibility check — the runtime clamp is what actually holds
# the guarantee, so being wrong here cannot let the tool self-release.
HANDSHAKE_FIXED_COST_S="${HANDSHAKE_FIXED_COST_S:-45}"

# Kept in hand so `release` is written comfortably before the tool gives up, covering
# the tool's own poll interval and the one-second granularity of SECONDS.
HANDSHAKE_SAFETY_S="${HANDSHAKE_SAFETY_S:-15}"

# Bash 3 (default on macOS) lacks associative arrays.
RESULT_NAMES=()
RESULT_STATES=()
SUBMITTED_JOB_IDS=()
CONFIG_KEYS_SET=()
JOB_ID=""
WORK_DIR=""
HANDSHAKE_DIR=""
VERDICT_DIR=""
CHECKPOINT_DIR=""
INPUT_DIR=""
INPUT_FILE=""
FLINK_CONF=""
FLINK_CONF_BACKUP=""
TM_PID_BEFORE=""
TM_RESOURCE_ID_BEFORE=""
RESTORED_BEFORE=""
RELEASE_DEADLINE_S=""
HANDSHAKE_DEADLINE_AT=""

# ---------------------------------------------------------------------------
# A reused Flink home carries someone's own config.yaml. set_config_key deletes any
# line already present for a key before appending ours, so reverting key by key
# cannot put back a value the installation already had: the whole file is copied
# aside before the first edit and copied back at exit. config.yaml is the only
# thing copied aside — the jars this run stages into lib/ are not.
#
# The copy carries mode as well as content. delete_config_key rewrites through
# mktemp and mv, which replaces the file with a 0600 temp file, so restoring the
# bytes alone would leave the installation's config readable only by this user.
#
# The copy lives under TMPDIR rather than in WORK_DIR or in the installation:
# WORK_DIR does not exist yet when the copy is taken and is removed on a clean
# exit, and writing into conf/ mutates the directory being protected.
# ---------------------------------------------------------------------------
backup_flink_conf() {
    FLINK_CONF_BACKUP="$(mktemp "${TMPDIR:-/tmp}/flink-agents-config.yaml.XXXXXX")" || {
        log_error "Could not create a temporary file to copy $FLINK_CONF aside"
        exit 1
    }
    # `if !` rather than a bare cp: set -e would abort before the diagnostic prints.
    if ! cp -p "$FLINK_CONF" "$FLINK_CONF_BACKUP"; then
        rm -f "$FLINK_CONF_BACKUP"
        FLINK_CONF_BACKUP=""
        log_error "Could not copy $FLINK_CONF aside. Refusing to edit an installation that cannot be put back."
        exit 1
    fi
    log_info "Copied $FLINK_CONF aside for restore at exit"
}

restore_flink_conf() {
    # Guarded on the copy, not on the destination: a config.yaml that went missing
    # mid-run is exactly when the restore is needed, and cp recreates it. The
    # explicit `|| return 0` is load-bearing under bash 3, where a bare [[ ]] as a
    # non-final command does not trigger errexit.
    [[ -n "$FLINK_CONF" && -n "$FLINK_CONF_BACKUP" && -f "$FLINK_CONF_BACKUP" ]] || return 0
    if cp -p "$FLINK_CONF_BACKUP" "$FLINK_CONF"; then
        log_info "Restored $FLINK_CONF from the pre-run copy"
        rm -f "$FLINK_CONF_BACKUP"
    else
        # Keep the only surviving original and say where it is.
        log_warn "Could not restore $FLINK_CONF; the pre-run copy is kept at $FLINK_CONF_BACKUP"
        return 1
    fi
}

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
            # If the TaskManager was killed and never replaced, this prints
            # "No taskexecutor daemon (pid: N) is running anymore" into the archive.
            # That line is expected here and is not the failure.
            log_info "Stopping Flink cluster"
            "$FLINK_HOME/bin/stop-cluster.sh" >/dev/null 2>&1 || true
        fi

        # Archive the whole log directory, never named files: `kill -9` leaves the
        # dead TaskManager's pid in the pid file, so the replacement writes to a
        # higher log index and a named copy would miss the post-restore TaskManager.
        if [[ -d "$FLINK_HOME/log" ]]; then
            local log_archive="$ROOT_DIR/flink-logs-$(date +%Y%m%d-%H%M%S).tar.gz"
            tar -czf "$log_archive" -C "$FLINK_HOME" log >/dev/null 2>&1 \
                && log_info "Flink logs archived to: $log_archive" \
                || log_warn "Failed to archive Flink logs"
        fi
    fi

    # Put the installation's config.yaml back exactly as it was, mode included.
    # Restoring the whole file also drops the keys this run appended, so a second
    # run against a reused Flink home finds no duplicate top-level key — which
    # would be a hard YAML parse failure that stops the cluster from starting at
    # all.
    #
    # Tested inside the `if` so a failure cannot trip errexit here: aborting the
    # trap would skip print_summary, which is what reports the results and settles
    # the exit status. A run that left the installation mutated is not a success,
    # so a restore failure with nothing else to report becomes the exit code.
    if ! restore_flink_conf && (( exit_code == 0 )); then
        exit_code=1
    fi

    # The killed TaskManager's entry stays in /tmp/flink-*-taskexecutor.pid, which
    # only nudges the next local run's log-file index upward. That file is shared by
    # any Flink cluster this user runs, so it is left alone rather than risking a
    # concurrent cluster's shutdown for a cosmetic gain.

    # The work directory holds the verdict file, the handshake markers and the
    # checkpoints — everything needed to explain a failure. Remove it only when the
    # run both recorded assertions and is exiting cleanly; otherwise keep it and say
    # where it is. A non-zero exit code is its own reason to keep: the setup steps and
    # several assertions abort under set -e without recording anything, so judging by
    # the recorded results alone would delete the diagnostics for exactly those.
    if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
        local keep=0 state
        for state in "${RESULT_STATES[@]:-}"; do
            [[ "$state" == "FAIL" ]] && keep=1
        done
        (( ${#RESULT_STATES[@]} == 0 )) && keep=1
        (( exit_code != 0 )) && keep=1
        if (( keep == 1 )); then
            log_warn "Keeping diagnostics in $WORK_DIR (verdict, handshake markers, checkpoints)"
        else
            rm -rf "$WORK_DIR"
        fi
    fi

    print_summary
    exit "$exit_code"
}
trap cleanup EXIT

print_summary() {
    log_section "Test summary"
    local total=${#RESULT_NAMES[@]}
    if (( total == 0 )); then
        # Exit non-zero rather than return: a run that recorded no assertion verified
        # nothing, and reporting that as success is the precise failure this whole
        # script exists to make impossible.
        log_error "No assertion was recorded, so nothing was verified"
        exit 1
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
        log_error "$failed assertion(s) failed"
        exit 1
    fi
}

record_result() {
    RESULT_NAMES+=("$1")
    RESULT_STATES+=("$2")
}

# ---------------------------------------------------------------------------
# JSON extraction. python3 rather than jq, whose presence on the runner is not
# guaranteed.
#
# Exit 1 means "I could not read this": empty body, unparsable JSON, a path that
# does not exist, or a leaf of the wrong type. Callers MUST keep that distinct
# from "I read a value and did not like it yet" — reporting the first as the
# second turns a wrong field name into a phantom timeout.
# ---------------------------------------------------------------------------
json_query() {
    local mode="$1" path="$2"
    python3 -c '
import json
import sys

mode, path = sys.argv[1], sys.argv[2]
raw = sys.stdin.read()
if not raw.strip():
    sys.exit(1)
try:
    doc = json.loads(raw)
except ValueError:
    sys.exit(1)

# The cluster config endpoint answers with a list of {"key":..,"value":..} pairs,
# and its keys contain dots, so it cannot go through the path walker below.
if mode == "conf":
    if not isinstance(doc, list):
        sys.exit(1)
    for item in doc:
        if isinstance(item, dict) and item.get("key") == path:
            value = item.get("value")
            print("" if value is None else value)
            sys.exit(0)
    sys.exit(1)

node = doc
for part in path.split("."):
    if isinstance(node, list) and part.isdigit() and int(part) < len(node):
        node = node[int(part)]
    elif isinstance(node, dict) and part in node:
        node = node[part]
    else:
        sys.exit(1)

if mode == "nullable":
    # The key must be present; only its value may be null. A missing key is a
    # read failure, so a misspelled field cannot masquerade as "not yet".
    print("null" if node is None else "present")
elif mode == "len":
    if not isinstance(node, list):
        sys.exit(1)
    print(len(node))
elif mode == "int":
    if isinstance(node, bool) or not isinstance(node, int):
        sys.exit(1)
    print(node)
elif mode == "str":
    if not isinstance(node, str):
        sys.exit(1)
    print(node)
else:
    sys.exit(2)
' "$mode" "$path"
}

rest_get() {
    curl -fsS --max-time "$CURL_MAX_TIME" "$REST_URL$1" 2>/dev/null
}

rest_field() {
    local path="$1" mode="$2" field="$3"
    rest_get "$path" | json_query "$mode" "$field"
}

# ---------------------------------------------------------------------------
# Compare an observed value against a spec: eq:V, ge:N, nonnull.
#
# Returns 0 on a match, 1 on a mismatch, and 2 when the comparison cannot be
# evaluated at all — an unknown operator, or a non-integer operand for ge. Callers
# must keep 2 apart from 1: the first means the spec is wrong, the second means the
# condition is not satisfied yet.
# ---------------------------------------------------------------------------
value_matches() {
    local observed="$1" spec="$2"
    local op="${spec%%:*}" want="${spec#*:}"
    case "$op" in
        eq)      [[ "$observed" == "$want" ]] ;;
        ge)
            # (( )) evaluates its operands as shell arithmetic, so a non-numeric
            # observation would be treated as a variable name and abort the script
            # under set -u instead of simply not matching. Return 2 so the caller can
            # tell "cannot compare" from "does not match".
            if [[ ! "$observed" =~ ^-?[0-9]+$ ]] || [[ ! "$want" =~ ^-?[0-9]+$ ]]; then
                log_error "value_matches: 'ge' needs two integers, got observed='$observed' want='$want'"
                return 2
            fi
            (( observed >= want ))
            ;;
        nonnull) [[ "$observed" == "present" ]] ;;
        *)       log_error "value_matches: unknown comparison '$spec'"; return 2 ;;
    esac
}

# ---------------------------------------------------------------------------
# Poll a REST field until it satisfies a spec.
#
#   $1 label        human name of the step, used in both failure messages
#   $2 path         REST path, e.g. /jobs/<id>/checkpoints
#   $3 probe        "mode:field" that must ALWAYS parse on a healthy endpoint
#   $4 target       "mode:field" being tested
#   $5 spec         comparison, see value_matches
#   $6 timeout      seconds
#
# The probe is what separates the two failure modes. If the probe never parses we
# are not talking to the endpoint we think we are; if the probe parses but the
# target never does, the target's field name is wrong (or this Flink build omits
# it) — neither is evidence that the condition was false.
# ---------------------------------------------------------------------------
wait_for_rest() {
    local label="$1" path="$2" probe="$3" target="$4" spec="$5" timeout="$6"
    local probe_mode="${probe%%:*}" probe_field="${probe#*:}"
    local target_mode="${target%%:*}" target_field="${target#*:}"

    local probe_seen=0 target_seen=0 last=""
    local body value rc
    # Deadline, not accumulated sleeps: curl and the two python3 spawns per iteration
    # are not free, so counting only the sleeps would let a nominal budget overrun by
    # several times against a slow endpoint — and these budgets are what keep the
    # recovery inside the tool's own release deadline.
    local deadline=$((SECONDS + timeout))
    log_info "$label: polling GET $path for $target_field ($spec), budget ${timeout}s"
    while (( SECONDS < deadline )); do
        body=$(rest_get "$path") || body=""
        if [[ -n "$body" ]]; then
            if printf '%s' "$body" | json_query "$probe_mode" "$probe_field" >/dev/null; then
                probe_seen=1
            fi
            if value=$(printf '%s' "$body" | json_query "$target_mode" "$target_field"); then
                target_seen=1
                last="$value"
                rc=0
                value_matches "$value" "$spec" || rc=$?
                if (( rc == 0 )); then
                    log_ok "$label: $target_field is $value"
                    return 0
                elif (( rc > 1 )); then
                    log_error "$label: comparison '$spec' cannot be evaluated against '$value'"
                    return 1
                fi
            fi
        fi
        sleep "$POLL_INTERVAL"
    done

    # Order matters: an observed target is reported even when the probe never parsed,
    # because in that state the condition really was evaluated and really was false,
    # and claiming otherwise would withhold the one value a reader needs.
    if (( target_seen == 1 )); then
        log_error "$label: '$target_field' from GET $REST_URL$path was last '$last' after ${timeout}s and never satisfied '$spec'."
        if (( probe_seen == 0 )); then
            log_warn "$label: the probe field '$probe_field' never parsed, so the response shape is only partly as expected."
        fi
    elif (( probe_seen == 1 )); then
        log_error "$label: parsed '$probe_field' from GET $REST_URL$path, so the endpoint is right, but never parsed '$target_field' in ${timeout}s. Either the field name is wrong or this Flink build omits it. This is NOT evidence that the condition was false."
    else
        log_error "$label: never parsed '$probe_field' or '$target_field' from GET $REST_URL$path in ${timeout}s. The endpoint did not answer, or its response shape is not what this script expects. This is NOT evidence that the condition was false."
    fi
    return 1
}

# ---------------------------------------------------------------------------
# Wait for an exact filename. Never a glob: every file the job publishes is
# written to "<name>.tmp" and renamed, so a crash between the two steps leaves a
# permanent ".tmp" twin that a glob would happily read as the real thing.
# ---------------------------------------------------------------------------
wait_for_file() {
    local label="$1" file="$2" timeout="$3"
    local deadline=$((SECONDS + timeout))
    log_info "$label: waiting for $file, budget ${timeout}s"
    while (( SECONDS < deadline )); do
        if [[ -f "$file" ]]; then
            log_ok "$label: $file appeared"
            return 0
        fi
        sleep "$POLL_INTERVAL"
    done
    local dir
    dir="$(dirname "$file")"
    log_error "$label: $file did not appear within ${timeout}s"
    if [[ -f "$file.tmp" ]]; then
        log_error "$label: found a leftover $file.tmp — the writer was interrupted between write and rename"
    fi
    if [[ -d "$dir" ]]; then
        local listing
        listing=$(find "$dir" -maxdepth 1 -mindepth 1 2>/dev/null | sed 's|.*/||' | sort | tr '\n' ' ')
        log_info "$label: $dir currently holds: $listing"
    else
        log_error "$label: the directory $dir does not exist"
    fi
    return 1
}

install_flink_distribution() {
    local install_dir="$1"

    # Reuse install.sh's download, archive validation and extraction logic, but
    # stop before it installs a *released* Flink Agents JAR. That step derives its
    # download URL from the Flink version, and no such artifact is published for
    # every Flink version this test runs against — asking for one that does not
    # exist would abort the install. This test must run the artifacts built from
    # the current checkout anyway, which stage_dist_jars puts in place below.
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

# Replaces install.sh's setup_python_env, which would populate the venv with a
# released flink-agents. This creates a plain venv and nothing more; the packages
# arrive later, from tools/build.sh's `uv pip install dist/*.whl` and from
# install_built_python_package.
prepare_python_venv() {
    if [[ -f "$VENV_DIR/pyvenv.cfg" && -x "$VENV_DIR/bin/python" ]]; then
        # Reuse is unconditional on the interpreter: unlike install.sh, this accepts
        # a venv built by a Python outside the range python/pyproject.toml declares.
        log_info "Reusing Python venv: $VENV_DIR"
        return
    fi
    if [[ -e "$VENV_DIR" ]] \
        && [[ ! -d "$VENV_DIR" \
            || -n "$(find "$VENV_DIR" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
        log_error "VENV_DIR is not an empty directory or a valid venv: $VENV_DIR"
        return 1
    fi

    local python_bin="${PYTHON_BIN:-python3}"
    log_info "Creating Python venv: $VENV_DIR"
    "$python_bin" -m venv "$VENV_DIR"
}

install_flink() {
    log_section "Install Flink standalone (version $FLINK_VERSION)"

    # Anchor VENV_DIR to the repo so a fresh and a reused Flink installation share
    # one Python environment, and so later steps can address it by path.
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
            exit 1
        fi
        log_ok "Flink installed at: $FLINK_HOME"
    fi

    # The distribution ships the PyFlink jar under opt/, outside the lib/ directory
    # the cluster loads. install.sh copies it across as part of --enable-pyflink,
    # which is no longer reached from here, so the copy happens here — on both branches
    # above, since a reused FLINK_HOME need not have been prepared by install.sh.
    local pyflink_jar="$FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar"
    if [[ ! -f "$pyflink_jar" ]]; then
        log_error "PyFlink JAR not found in Flink distribution: $pyflink_jar"
        exit 1
    fi
    cp "$pyflink_jar" "$FLINK_HOME/lib/"

    prepare_python_venv

    # Both branches above fall through to here, so the venv is activated whether
    # FLINK_HOME was reused or freshly installed. That is load-bearing: the
    # TaskManager daemon inherits this shell's PATH and resolves a bare `python`
    # from it, and this script restarts the TaskManager mid-test — so an
    # unactivated shell would give the replacement TaskManager a different
    # interpreter from the original. Activation settles which interpreter that is
    # and nothing more — what is installed in it is install_built_python_package's
    # business, and that runs later.
    if [[ ! -x "$VENV_DIR/bin/python" ]]; then
        log_error "Expected Python venv not found at: $VENV_DIR"
        exit 1
    fi
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    log_ok "Activated Python venv: $VENV_DIR"

    FLINK_CONF="$FLINK_HOME/conf/config.yaml"
    if [[ ! -f "$FLINK_CONF" ]]; then
        log_error "Flink config not found: $FLINK_CONF"
        exit 1
    fi

    # Adjacent to the assignment on purpose, so FLINK_CONF is never set without a
    # copy of the file existing and every later edit is reversible.
    backup_flink_conf
}

# The wheel this run must exercise is the one tools/build.sh just produced, so it is
# installed from python/dist rather than from PyPI. apache-flink is pinned to the
# Flink version the cluster runs, because the submitting client and the TaskManager
# both execute this same interpreter.
install_built_python_package() {
    local wheel
    wheel=$(find "$ROOT_DIR/python/dist" -maxdepth 1 -name '*.whl' | head -n 1)
    if [[ -z "$wheel" ]]; then
        log_error "Python wheel not found after build in: $ROOT_DIR/python/dist"
        return 1
    fi

    log_info "Installing $(basename "$wheel") and apache-flink==$FLINK_VERSION into $VENV_DIR"
    "$VENV_DIR/bin/python" -m pip install --quiet \
        "$wheel" "apache-flink==$FLINK_VERSION"

    if ! "$VENV_DIR/bin/python" -c 'import flink_agents, pyflink' >/dev/null 2>&1; then
        log_error "The built Flink Agents wheel or PyFlink is not importable from: $VENV_DIR/bin/python"
        return 1
    fi

    export PYFLINK_CLIENT_EXECUTABLE="$VENV_DIR/bin/python"
    log_ok "Installed the built wheel and PyFlink into: $VENV_DIR"
}

build_project() {
    log_section "Build flink-agents (Java + Python)"
    (
        cd "$ROOT_DIR"
        SKIP_SPOTLESS_CHECK=true bash tools/build.sh
    )
    install_built_python_package
    log_ok "Build completed"
}

stage_dist_jars() {
    log_section "Stage dist uber jar into \$FLINK_HOME/lib"

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

    # Drop any flink-agents-dist jar already in lib/ before copying. A reused
    # FLINK_HOME may still carry one — from an earlier tools/install.sh run, or from
    # an earlier run of this test at a different project version — and a jar of a
    # different version has a different filename, so the copy below would not
    # replace it and both would sit on the classpath.
    rm -f "$FLINK_HOME/lib/"/flink-agents-dist-*.jar
    cp "$flink_jar"  "$FLINK_HOME/lib/"
    log_ok "Staged: $(basename "$flink_jar")"
}

prepare_work_dirs() {
    log_section "Create input, handshake, verdict and checkpoint directories"
    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/flink-agents-recovery.XXXXXX")"
    HANDSHAKE_DIR="$WORK_DIR/handshake"
    VERDICT_DIR="$WORK_DIR/verdict"
    CHECKPOINT_DIR="$WORK_DIR/checkpoints"
    INPUT_DIR="$WORK_DIR/input"
    # The name is load-bearing. Flink's default file filter skips any name beginning
    # with '.' or '_', and applies that even to a path the source was handed directly,
    # which would leave the job running with nothing to read and nothing logged.
    INPUT_FILE="$INPUT_DIR/trigger.txt"
    # The job must never create these itself: a tool that provisions its own
    # handshake directory cannot tell "the harness set me up" from "my path is wrong".
    mkdir -p "$HANDSHAKE_DIR" "$VERDICT_DIR" "$CHECKPOINT_DIR" "$INPUT_DIR"

    # One line is all the source needs: the job builds the record from a constant it
    # shares with the assertion and ignores this content. Written under a temporary
    # name and renamed, so the file is whole the instant it appears under the name the
    # source reads. Submission is several steps away, and that distance is what
    # actually orders the two — the rename is redundancy, not the ordering mechanism.
    printf 'trigger\n' > "$INPUT_FILE.tmp"
    mv "$INPUT_FILE.tmp" "$INPUT_FILE"
    # An empty file produces no record and no error. Nothing that depends on a record
    # then runs, the agent's identity report included, so the run surfaces as the
    # runtime-identity wait expiring with nothing pointing at the input. A wrong path
    # needs no guard here: enumeration is eager, so the job fails within seconds with
    # "Could not enumerate file splits".
    if [[ ! -s "$INPUT_FILE" ]]; then
        log_error "Trigger file is missing or empty: $INPUT_FILE"
        return 1
    fi

    log_ok "Work directory: $WORK_DIR"
}

# ---------------------------------------------------------------------------
# Config editing. Delete-then-append, so applying the same key twice is a no-op
# rather than a duplicate YAML key.
#
# A flat dotted key at column 0 is read correctly regardless of the nested blocks
# the shipped file uses, because Flink flattens the parsed document before
# building its Configuration. Appending (never prepending) also means our value
# wins any collision with a nested block, since the flattened map is populated in
# document order.
# ---------------------------------------------------------------------------
delete_config_key() {
    local config_key="$1"
    local tmp
    tmp="$(mktemp)"
    # grep -v exits 1 when it filters everything out, which set -e would treat as
    # fatal. The dots in the key are regex "any char"; harmless for these keys.
    grep -v "^${config_key}: " "$FLINK_CONF" > "$tmp" || true
    mv "$tmp" "$FLINK_CONF"
}

set_config_key() {
    local config_key="$1" value="$2"
    delete_config_key "$config_key"
    printf '%s: %s\n' "$config_key" "$value" >> "$FLINK_CONF"
    CONFIG_KEYS_SET+=("$config_key")
}

configure_flink() {
    log_section "Configure checkpointing and restart strategy"

    # Without an interval, checkpointing is simply off — the option has no default.
    # That is the one misconfiguration that would let this whole test pass having
    # verified nothing, which is why the effective value is read back below.
    set_config_key "execution.checkpointing.interval" "${CHECKPOINT_INTERVAL_MS}ms"
    set_config_key "execution.checkpointing.dir" "file://$CHECKPOINT_DIR"
    # Every other Python e2e test in this repo disables restarts. This one must not:
    # without a restart strategy the job dies on the TaskManager loss instead of
    # recovering from its checkpoint.
    set_config_key "restart-strategy.type" "fixed-delay"
    set_config_key "restart-strategy.fixed-delay.attempts" "$RESTART_ATTEMPTS"
    set_config_key "resourcemanager.standalone.start-up-time" "$STANDALONE_STARTUP_TIME"

    log_ok "Appended ${#CONFIG_KEYS_SET[@]} config key(s) to $FLINK_CONF"
}

# ---------------------------------------------------------------------------
# Read the keys back from the running JobManager. Writing to a file only proves
# we wrote to a file.
#
# What this does and does not establish: /jobmanager/config echoes the loaded
# configuration including keys Flink does not recognize, so a value matching here
# proves the append reached the configuration the JobManager parsed — not that the
# option has any effect. The one key where that distinction matters is the
# checkpoint interval, and it is asserted separately and authoritatively by
# assert_checkpointing_enabled once a job exists.
# ---------------------------------------------------------------------------
assert_effective_config() {
    log_section "Verify the JobManager loaded the config we wrote"

    local exact_keys=(
        "execution.checkpointing.dir=file://$CHECKPOINT_DIR"
        "restart-strategy.type=fixed-delay"
        "restart-strategy.fixed-delay.attempts=$RESTART_ATTEMPTS"
        "resourcemanager.standalone.start-up-time=$STANDALONE_STARTUP_TIME"
    )

    local body
    body=$(rest_get "/jobmanager/config") || body=""
    if [[ -z "$body" ]]; then
        log_error "Could not read GET $REST_URL/jobmanager/config"
        record_result "effective-config" "FAIL"
        return 1
    fi

    local ok=1 entry key want got
    for entry in "${exact_keys[@]}"; do
        key="${entry%%=*}"; want="${entry#*=}"
        if got=$(printf '%s' "$body" | json_query conf "$key"); then
            if [[ "$got" == "$want" ]]; then
                log_ok "config $key = $got"
            else
                log_error "config $key is '$got', expected '$want'"
                ok=0
            fi
        else
            log_error "config $key is absent from the cluster configuration — the append did not take effect"
            ok=0
        fi
    done

    if (( ok == 0 )); then
        record_result "effective-config" "FAIL"
        return 1
    fi
    record_result "effective-config" "PASS"
}

# ---------------------------------------------------------------------------
# The authoritative check that checkpointing is actually on for this job.
#
# /jobs/{id}/checkpoints/config is served from the job's own CheckpointCoordinator
# configuration rather than from the raw config file, so unlike /jobmanager/config
# it cannot echo a key back that Flink ignored. It 404s outright when the job has
# no checkpointing, and reports the interval in exact milliseconds.
#
# This is the guard on the one misconfiguration that would let the whole test pass
# having verified nothing.
# ---------------------------------------------------------------------------
assert_checkpointing_enabled() {
    log_section "Verify checkpointing is enabled for this job"

    local body
    body=$(rest_get "/jobs/$JOB_ID/checkpoints/config") || body=""
    if [[ -z "$body" ]]; then
        log_error "Could not read GET $REST_URL/jobs/$JOB_ID/checkpoints/config. This endpoint answers only when the job has checkpointing configured, so an empty or 404 response means checkpointing is off and nothing would be captured to recover."
        record_result "checkpointing-enabled" "FAIL"
        return 1
    fi

    local interval
    if ! interval=$(printf '%s' "$body" | json_query int "interval"); then
        log_error "Could not read 'interval' from GET $REST_URL/jobs/$JOB_ID/checkpoints/config; the response was: $body"
        record_result "checkpointing-enabled" "FAIL"
        return 1
    fi
    if [[ "$interval" != "$CHECKPOINT_INTERVAL_MS" ]]; then
        log_error "The job's checkpoint interval is ${interval}ms, expected ${CHECKPOINT_INTERVAL_MS}ms. The value written to $FLINK_CONF is not the value in effect."
        record_result "checkpointing-enabled" "FAIL"
        return 1
    fi
    log_ok "checkpointing-enabled: interval ${interval}ms"

    # The two-checkpoint containment gate assumes checkpoints do not overlap, which
    # is Flink's default of one concurrent checkpoint. Read from the same
    # authoritative endpoint rather than inferred.
    local max_concurrent
    if max_concurrent=$(printf '%s' "$body" | json_query int "max_concurrent"); then
        log_info "checkpointing-enabled: max_concurrent = $max_concurrent"
        (( max_concurrent == 1 )) || log_warn "More than one concurrent checkpoint is allowed, so the two-checkpoint containment gate is weaker than intended: the second completion no longer implies it started after the first finished."
    else
        log_warn "Could not read 'max_concurrent'; the containment gate assumes it is 1"
    fi

    record_result "checkpointing-enabled" "PASS"
}

start_cluster() {
    log_section "Start Flink standalone cluster"
    "$FLINK_HOME/bin/start-cluster.sh"

    log_info "Waiting for JobManager REST API at $REST_URL ..."
    local start=$SECONDS
    local deadline=$((start + CLUSTER_TIMEOUT))
    # The deadline is tested at the top of the iteration, so the last probe and its
    # sleep run past it. The message reports the time waited rather than the budget.
    while (( SECONDS < deadline )); do
        if rest_get "/overview" >/dev/null; then
            log_ok "Flink cluster is up"
            return 0
        fi
        sleep "$POLL_INTERVAL"
    done

    log_error "Flink cluster did not become ready within $((SECONDS - start))s. A duplicate key in $FLINK_CONF is the usual cause; check the JobManager log for a YAML parse error."
    exit 1
}

# ---------------------------------------------------------------------------
# The tool releases itself after RELEASE_TIMEOUT_S whether or not this script has
# finished the kill/restart cycle, so everything between "tool parked" and "release
# created" has to fit inside that. The deadline is read from the payload module
# rather than restated, so the two cannot drift apart.
#
# The guarantee is held by measurement, not prediction. start_handshake_clock stamps
# a real wall-clock deadline the moment the tool parks, and every charged step takes
# the smaller of its configured budget and the time actually left. That way a cost
# nobody modelled — a slow REST call, a sluggish TaskManager start, anything — has
# already consumed real time and simply leaves the next step less to spend. Summing
# nominal timeouts could not do this: the sum says nothing about what the run spent.
# ---------------------------------------------------------------------------
read_release_deadline() {
    RELEASE_DEADLINE_S=$(PYTHONPATH="$ROOT_DIR/python" "$VENV_DIR/bin/python" -c \
        'from flink_agents.e2e_tests.e2e_tests_integration.checkpoint_recovery_agent import RELEASE_TIMEOUT_S; print(int(RELEASE_TIMEOUT_S))') || {
        log_error "Could not read RELEASE_TIMEOUT_S from the payload module. The job would not be importable on the TaskManager either."
        exit 1
    }
}

start_handshake_clock() {
    HANDSHAKE_DEADLINE_AT=$((SECONDS + RELEASE_DEADLINE_S - HANDSHAKE_SAFETY_S))
    log_info "Handshake clock started: ${RELEASE_DEADLINE_S}s tool deadline less ${HANDSHAKE_SAFETY_S}s safety, so $((RELEASE_DEADLINE_S - HANDSHAKE_SAFETY_S))s to reach the release"
}

# Seconds left before the tool would release itself. Never negative.
handshake_budget_left() {
    local left=$((HANDSHAKE_DEADLINE_AT - SECONDS))
    (( left < 0 )) && left=0
    printf '%s' "$left"
}

# The smaller of a step's configured ceiling and the time actually remaining. Returns
# 1 when nothing is left, so the caller fails on the real reason rather than starting
# a wait that cannot finish.
charged_timeout() {
    local label="$1" configured="$2" left
    left="$(handshake_budget_left)"
    if (( left <= 0 )); then
        log_error "$label: the tool's ${RELEASE_DEADLINE_S}s release deadline elapsed before this step began, so the tool has already released itself and the recovery window is gone. Earlier steps ran slower than their budgets allow for."
        return 1
    fi
    if (( configured > left )); then
        log_warn "$label: budget trimmed from ${configured}s to ${left}s, the time left before the tool releases itself"
        printf '%s' "$left"
    else
        printf '%s' "$configured"
    fi
}

# Pre-flight feasibility check, run before the job is submitted so an unusable
# configuration fails in seconds rather than twenty minutes in.
#
# This one predicts, so it is deliberately conservative: each charged wait is billed
# its ceiling plus one poll interval and one request timeout, because the loop tests
# its deadline at the top and a poll already in flight can overrun by that much. The
# costs belonging to no single wait are billed as HANDSHAKE_FIXED_COST_S. Being wrong
# here can only make this check over- or under-eager; it cannot let the tool
# self-release, because that is the runtime clamp's job.
assert_handshake_budget() {
    log_section "Check the recovery budget against the tool's deadline"

    read_release_deadline

    local per_wait_overrun=$((POLL_INTERVAL + CURL_MAX_TIME))
    local ceilings=$((CHECKPOINT_TIMEOUT + TM_GONE_TIMEOUT + TM_UP_TIMEOUT + RESTORE_TIMEOUT))
    local predicted=$((ceilings + 4 * per_wait_overrun + HANDSHAKE_FIXED_COST_S))
    local allowed=$((RELEASE_DEADLINE_S - HANDSHAKE_SAFETY_S))

    # The tool's deadline is named on the success path too, not only in the failure
    # message: which deadline the run was measured against is the fact a reader needs,
    # and it comes from the payload module rather than from anything restated here.
    log_info "Charged waits total ${ceilings}s of ceilings, plus ${per_wait_overrun}s possible overrun each and ${HANDSHAKE_FIXED_COST_S}s of unattributed cost: ${predicted}s predicted against ${allowed}s allowed, from the tool's ${RELEASE_DEADLINE_S}s release deadline less ${HANDSHAKE_SAFETY_S}s safety"

    if (( predicted > allowed )); then
        log_error "The recovery sequence could take up to ${predicted}s of wall clock, which exceeds the ${allowed}s available before the tool releases itself (${RELEASE_DEADLINE_S}s deadline less ${HANDSHAKE_SAFETY_S}s safety). The run would fail as a handshake timeout and misattribute harness slowness to the handshake. Lower CHECKPOINT_TIMEOUT / TM_GONE_TIMEOUT / TM_UP_TIMEOUT / RESTORE_TIMEOUT, or CURL_MAX_TIME."
        exit 1
    fi
    log_ok "Recovery budget fits with $((allowed - predicted))s of predicted slack"
}

extract_job_id() {
    # "Job has been submitted with JobID <id>"
    grep -Eo 'JobID [0-9a-f]{32}' "$1" | tail -n 1 | awk '{print $2}'
}

submit_job() {
    log_section "Submit the recovery job"

    local script_path="$ROOT_DIR/python/$JOB_MODULE"
    if [[ ! -f "$script_path" ]]; then
        log_error "Job program not found: $script_path"
        exit 1
    fi

    local purelib
    purelib=$("$VENV_DIR/bin/python" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

    local out rc=0
    out=$(mktemp)
    # -pyexec / -pypath are the option names the Flink CLI accepts (the long forms
    # are --pyExecutable and --pyPythonPath). The repo tree precedes the installed
    # wheel on the path, which is priority rather than isolation, so the version
    # this run actually imported is asserted from the job's own report below.
    timeout "$SUBMIT_TIMEOUT" "$FLINK_HOME/bin/flink" run \
            --detached \
            -pyexec "$VENV_DIR/bin/python" \
            -pypath "$ROOT_DIR/python:$purelib" \
            -py "$script_path" \
            --input-file "$INPUT_FILE" \
            --handshake-dir "$HANDSHAKE_DIR" \
            --verdict-dir "$VERDICT_DIR" >"$out" 2>&1 || rc=$?
    cat "$out"

    if (( rc != 0 )); then
        log_error "Job submission failed (exit $rc)"
        rm -f "$out"
        exit 1
    fi

    JOB_ID=$(extract_job_id "$out") || true
    rm -f "$out"
    if [[ -z "$JOB_ID" ]]; then
        log_error "Could not extract the job id from the submission output"
        exit 1
    fi
    SUBMITTED_JOB_IDS+=("$JOB_ID")
    log_ok "Submitted (JobID: $JOB_ID)"
}

wait_job_running() {
    wait_for_rest "job-running" "/jobs/$JOB_ID" "str:state" "str:state" "eq:RUNNING" \
        "$JOB_RUNNING_TIMEOUT" || { record_result "job-running" "FAIL"; return 1; }
}

# ---------------------------------------------------------------------------
# The job writes an identity report from the TaskManager before it parks. This is
# a fail-fast gate: it catches a wrong flink-agents on the TaskManager's path
# before we spend the whole kill/restart cycle. It describes the process that is
# about to be killed; the copy that describes the process performing the
# assertions travels inside the verdict record.
# ---------------------------------------------------------------------------
assert_runtime_identity() {
    log_section "Check which flink-agents the TaskManager imported"

    local identity="$HANDSHAKE_DIR/runtime-identity.json"
    if ! wait_for_file "runtime-identity" "$identity" "$IDENTITY_TIMEOUT"; then
        record_result "runtime-identity" "FAIL"
        return 1
    fi

    local version module_file
    if ! version=$(json_query str "flink_agents_version" < "$identity"); then
        log_error "runtime-identity: could not read 'flink_agents_version' from $identity"
        cat "$identity" >&2 || true
        record_result "runtime-identity" "FAIL"
        return 1
    fi
    if ! module_file=$(json_query str "flink_agents_api_file" < "$identity"); then
        log_error "runtime-identity: could not read 'flink_agents_api_file' from $identity"
        cat "$identity" >&2 || true
        record_result "runtime-identity" "FAIL"
        return 1
    fi
    log_info "TaskManager imported flink_agents from: $module_file"

    if [[ "$version" != "$EXPECTED_AGENTS_VERSION" ]]; then
        log_error "runtime-identity: TaskManager reports flink-agents $version, expected $EXPECTED_AGENTS_VERSION. The job ran against a different installation than the one just built."
        record_result "runtime-identity" "FAIL"
        return 1
    fi

    # The version alone comes from distribution metadata and cannot tell two trees
    # apart that both call themselves 0.3.dev0 — an installed wheel and this working
    # tree would report the same string. The api-file probe is what identifies the
    # tree, so assert it points inside the checkout -pypath was pointed at.
    local expected_prefix="$ROOT_DIR/python/flink_agents/api/"
    if [[ "$module_file" != "$expected_prefix"* ]]; then
        log_error "runtime-identity: the TaskManager imported flink_agents.api from '$module_file', which is not under '$expected_prefix'. The job exercised a different copy of the code than the one in this checkout, so a pass would not be evidence about these sources."
        record_result "runtime-identity" "FAIL"
        return 1
    fi

    log_ok "runtime-identity: flink-agents $version from $module_file"
    record_result "runtime-identity" "PASS"
}

wait_tool_parked() {
    log_section "Wait for the job to park inside the tool call"
    if ! wait_for_file "tool-parked" "$HANDSHAKE_DIR/tool-entered" "$TOOL_ENTERED_TIMEOUT"; then
        log_error "tool-parked: the agent never reached the blocking tool, so the tool-call context was never written and there is nothing for a checkpoint to capture."
        record_result "tool-parked" "FAIL"
        return 1
    fi
    # From here until `release` is written, wall clock is charged against the tool's
    # own deadline.
    start_handshake_clock
    record_result "tool-parked" "PASS"
}

# ---------------------------------------------------------------------------
# Require TWO further completed checkpoints, not one. A checkpoint already in
# flight when the tool parked may complete afterwards while having started before
# the agent's memory was flushed, so a single increment does not prove the
# payload was captured. With one concurrent checkpoint allowed, the second
# increment cannot have started before the first completed, which is after the
# tool parked.
# ---------------------------------------------------------------------------
wait_for_checkpoint_containing_payload() {
    log_section "Wait for a checkpoint taken while the run is parked"

    local baseline
    if ! baseline=$(rest_field "/jobs/$JOB_ID/checkpoints" int "counts.completed"); then
        log_error "checkpoint-baseline: could not read 'counts.completed' from GET $REST_URL/jobs/$JOB_ID/checkpoints"
        record_result "checkpoint-containment" "FAIL"
        return 1
    fi
    log_info "checkpoint-baseline: $baseline completed checkpoints at the moment the tool parked"

    local budget
    if ! budget="$(charged_timeout "checkpoint-containment" "$CHECKPOINT_TIMEOUT")"; then
        record_result "checkpoint-containment" "FAIL"
        return 1
    fi

    if ! wait_for_rest "checkpoint-containment" "/jobs/$JOB_ID/checkpoints" \
            "int:counts.total" "int:counts.completed" "ge:$((baseline + 2))" \
            "$budget"; then
        log_error "checkpoint-containment: fewer than two checkpoints completed while the run was parked. Checkpointing is enabled, asserted earlier against the job's own checkpoint config, so check next whether checkpoints start and then never finish: GET $REST_URL/jobs/$JOB_ID/checkpoints reports counts.total against counts.completed and counts.in_progress. Checkpoints that keep starting and stay in progress have been measured when the in-flight action occupies the task's mailbox thread, which is the state tool-call.async being off would produce."
        record_result "checkpoint-containment" "FAIL"
        return 1
    fi
    record_result "checkpoint-containment" "PASS"
}

record_taskmanager_identity() {
    log_section "Record the TaskManager's identity before killing it"

    local pids
    if command -v jps >/dev/null 2>&1; then
        pids=$(jps | awk '$2 == "TaskManagerRunner" { print $1 }')
    else
        log_warn "jps not on PATH; falling back to pgrep"
        pids=$(pgrep -f TaskManagerRunner || true)
    fi

    local count
    count=$(printf '%s\n' "$pids" | grep -c '[0-9]' || true)
    if [[ "$count" != "1" ]]; then
        log_error "Expected exactly one TaskManagerRunner process, found $count: $(printf '%s' "$pids" | tr '\n' ' ')"
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi
    TM_PID_BEFORE=$(printf '%s\n' "$pids" | head -n 1)

    if ! TM_RESOURCE_ID_BEFORE=$(rest_field "/taskmanagers" str "taskmanagers.0.id"); then
        log_error "Could not read 'taskmanagers.0.id' from GET $REST_URL/taskmanagers"
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi

    # Baseline for the restore wait, captured before the kill for the same reason the
    # checkpoint gate captures one: an absolute ">= 1" would be satisfied by a restore
    # that had already happened for some other reason, so the assertion has to be that
    # the count went up across *our* kill.
    if ! RESTORED_BEFORE=$(rest_field "/jobs/$JOB_ID/checkpoints" int "counts.restored"); then
        log_error "Could not read 'counts.restored' from GET $REST_URL/jobs/$JOB_ID/checkpoints"
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi

    log_ok "TaskManager pid $TM_PID_BEFORE, resource id $TM_RESOURCE_ID_BEFORE, restores so far $RESTORED_BEFORE"
}

kill_taskmanager() {
    log_section "Kill -9 the TaskManager"
    # SIGKILL, not the graceful stop taskmanager.sh would do: a clean shutdown is
    # not process recreation, and recreating the JVM is the whole point.
    kill -9 "$TM_PID_BEFORE"
    log_ok "Sent SIGKILL to $TM_PID_BEFORE"

    if kill -0 "$TM_PID_BEFORE" 2>/dev/null; then
        sleep "$POLL_INTERVAL"
    fi
    if kill -0 "$TM_PID_BEFORE" 2>/dev/null; then
        log_error "TaskManager pid $TM_PID_BEFORE is still alive after SIGKILL"
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi

    # Distinct from "the process is dead": this is the JobManager noticing, which
    # is what makes the subsequent restore a real recovery rather than a no-op.
    local budget
    if ! budget="$(charged_timeout "taskmanager-deregistered" "$TM_GONE_TIMEOUT")"; then
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi
    if ! wait_for_rest "taskmanager-deregistered" "/taskmanagers" \
            "len:taskmanagers" "len:taskmanagers" "eq:0" "$budget"; then
        log_error "taskmanager-deregistered: the JobManager still lists a TaskManager. Heartbeat detection normally takes about 20s with Flink's defaults."
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi
}

restart_taskmanager() {
    log_section "Start a replacement TaskManager"
    # Runs in this shell, which has the venv activated, so the replacement resolves
    # the same bare `python` the original did.
    "$FLINK_HOME/bin/taskmanager.sh" start

    local budget
    if ! budget="$(charged_timeout "taskmanager-registered" "$TM_UP_TIMEOUT")"; then
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi
    if ! wait_for_rest "taskmanager-registered" "/taskmanagers" \
            "len:taskmanagers" "len:taskmanagers" "ge:1" "$budget"; then
        log_error "taskmanager-registered: the replacement TaskManager never registered. Check the highest-numbered flink-*-taskexecutor-*.log in the archived logs."
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi

    local pid_after id_after
    if command -v jps >/dev/null 2>&1; then
        pid_after=$(jps | awk '$2 == "TaskManagerRunner" { print $1 }' | head -n 1)
    else
        pid_after=$(pgrep -f TaskManagerRunner | head -n 1 || true)
    fi
    id_after=$(rest_field "/taskmanagers" str "taskmanagers.0.id") || id_after=""

    if [[ -z "$pid_after" || "$pid_after" == "$TM_PID_BEFORE" ]]; then
        log_error "taskmanager-recreated: pid after restart is '$pid_after', before was '$TM_PID_BEFORE'. The JVM was not replaced, so nothing proves the Python interpreter was recreated."
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi
    if [[ -z "$id_after" || "$id_after" == "$TM_RESOURCE_ID_BEFORE" ]]; then
        log_error "taskmanager-recreated: resource id after restart is '$id_after', before was '$TM_RESOURCE_ID_BEFORE'."
        record_result "taskmanager-recreated" "FAIL"
        return 1
    fi

    log_ok "TaskManager replaced: pid $TM_PID_BEFORE -> $pid_after, id $TM_RESOURCE_ID_BEFORE -> $id_after"
    record_result "taskmanager-recreated" "PASS"
}

# ---------------------------------------------------------------------------
# counts.restored relative to the pre-kill baseline, not an absolute ">= 1", and
# never the numRestarts metric — numRestarts also increments on a restart that
# restored nothing, which is exactly the case this test must not mistake for
# success. latest.restored is then checked as a second, independent signal from the
# same document.
# ---------------------------------------------------------------------------
wait_for_restore() {
    log_section "Wait for a restore from checkpoint"

    local budget
    if ! budget="$(charged_timeout "restore" "$RESTORE_TIMEOUT")"; then
        record_result "restore" "FAIL"
        return 1
    fi

    if ! wait_for_rest "restore" "/jobs/$JOB_ID/checkpoints" \
            "int:counts.total" "int:counts.restored" "ge:$((RESTORED_BEFORE + 1))" \
            "$budget"; then
        log_error "restore: counts.restored never rose above the pre-kill baseline of $RESTORED_BEFORE. The job may have restarted from scratch, in which case nothing was recovered and the payload assertions would be meaningless."
        record_result "restore" "FAIL"
        return 1
    fi

    local latest
    if ! latest=$(rest_field "/jobs/$JOB_ID/checkpoints" nullable "latest.restored"); then
        log_error "restore: counts.restored rose, but 'latest.restored' could not be read from GET $REST_URL/jobs/$JOB_ID/checkpoints"
        record_result "restore" "FAIL"
        return 1
    fi
    if [[ "$latest" != "present" ]]; then
        log_error "restore: counts.restored rose above $RESTORED_BEFORE but latest.restored is null, which is contradictory — treating it as a failure rather than guessing which signal to trust."
        record_result "restore" "FAIL"
        return 1
    fi

    log_ok "restore: counts.restored rose above $RESTORED_BEFORE and latest.restored is populated"
    record_result "restore" "PASS"
}

release_tool() {
    log_section "Release the blocked tool"

    # The measured bound, checked once more at the point it matters. The clamp stops
    # any step starting past the deadline, but the last one can still overrun it, and
    # a release written after the tool gave up produces a confusing handshake failure
    # rather than a clear one.
    local left
    left="$(handshake_budget_left)"
    if (( left <= 0 )); then
        log_error "release: reached the release point with no time left before the tool's ${RELEASE_DEADLINE_S}s deadline. The recovery sequence was slower than its budget allows, so the tool has released itself and the transcript will show a handshake timeout instead of the real cause."
        record_result "release-in-time" "FAIL"
        return 1
    fi
    log_ok "release: ${left}s still in hand before the tool's deadline"
    record_result "release-in-time" "PASS"

    # Only now may the run finish: everything the assertions depend on is in a
    # completed checkpoint and has been restored into a new process.
    : > "$HANDSHAKE_DIR/release"
    log_ok "Created $HANDSHAKE_DIR/release"
}

job_state() {
    rest_field "/jobs/$JOB_ID" str "state" || printf 'UNREADABLE'
}

# ---------------------------------------------------------------------------
# The job publishes a single "verdict" field. Read that; do not recompute the
# conjunction from the component booleans here, or the pass condition would exist
# in two places and could disagree.
# ---------------------------------------------------------------------------
assert_verdict() {
    log_section "Read the verdict the job published"

    local verdict_file="$VERDICT_DIR/verdict.json"
    local deadline=$((SECONDS + VERDICT_TIMEOUT)) state
    while (( SECONDS < deadline )); do
        [[ -f "$verdict_file" ]] && break
        state=$(job_state)
        if [[ "$state" == "FAILED" || "$state" == "CANCELED" ]]; then
            log_warn "Job reached $state while waiting for the verdict"
            break
        fi
        sleep "$POLL_INTERVAL"
    done

    if [[ ! -f "$verdict_file" ]]; then
        log_error "verdict: $verdict_file was never written. The job finished or died without running its assertions, so nothing was verified."
        if [[ -f "$verdict_file.tmp" ]]; then
            log_error "verdict: found a leftover $verdict_file.tmp — the writer was interrupted between write and rename"
        fi
        log_info "verdict: job state is $(job_state)"
        record_result "verdict" "FAIL"
        return 1
    fi

    log_info "verdict file contents:"
    cat "$verdict_file" >&2 || true

    local verdict
    if ! verdict=$(json_query str "verdict" < "$verdict_file"); then
        log_error "verdict: $verdict_file has no readable 'verdict' field. The harness and the job disagree about the verdict format."
        record_result "verdict" "FAIL"
        return 1
    fi

    local observed_type
    observed_type=$(json_query str "blob_observed_type" < "$verdict_file") || observed_type="unreadable"
    log_info "verdict: the restored bytes value materialized as '$observed_type'"

    if [[ "$verdict" != "pass" ]]; then
        log_error "verdict: the job reported '$verdict'. The per-assertion booleans in the file above name which check failed."
        record_result "verdict" "FAIL"
        return 1
    fi
    log_ok "verdict: pass"
    record_result "verdict" "PASS"
}

main() {
    install_flink
    build_project
    stage_dist_jars
    prepare_work_dirs
    configure_flink
    start_cluster
    # A wrong effective config means checkpointing may be off, so stop here rather
    # than running a recovery test that cannot recover anything.
    assert_effective_config || return 0
    assert_handshake_budget
    submit_job

    # From here on a failed step records a FAIL and returns, so the EXIT trap still
    # archives the logs and print_summary turns the FAIL into a non-zero exit.
    wait_job_running          || return 0
    assert_checkpointing_enabled || return 0
    assert_runtime_identity   || return 0
    wait_tool_parked          || return 0
    wait_for_checkpoint_containing_payload || return 0
    record_taskmanager_identity || return 0
    kill_taskmanager        || return 0
    restart_taskmanager     || return 0
    wait_for_restore        || return 0
    release_tool            || return 0
    assert_verdict          || return 0
}

# The no-run hook lets a test source this file to exercise individual functions
# against fixtures without starting a cluster. Same mechanism, same spelling as
# tools/install.sh's FLINK_AGENTS_INSTALL_SH_NO_RUN.
if [[ "${FLINK_AGENTS_RECOVERY_SH_NO_RUN:-0}" != "1" ]]; then
    main "$@"
fi
