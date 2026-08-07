# Helpers for the checkpoint-recovery harness tests. Loaded via
# `load 'helpers/recovery'`.

# Sources test_checkpoint_recovery.sh with the no-run hook so main() is skipped.
load_recovery_sh() {
    export FLINK_AGENTS_RECOVERY_SH_NO_RUN=1
    REPO_ROOT="$(cd "${BATS_TEST_DIRNAME}/../../.." && pwd)"
    RECOVERY_SH="$REPO_ROOT/e2e-test/test-scripts/test_checkpoint_recovery.sh"

    # Sourcing arms the script's own EXIT trap, which cancels jobs, stops a Flink
    # cluster and calls print_summary — none of which belongs in a unit test.
    #
    # It must be removed, but removing it naively takes bats's EXIT trap with it,
    # and that trap is how bats reports each result. Without it a skipped or failing
    # test produces no output at all: it disappears from the run and the summary
    # counts it as never executed. Save bats's handler and restore it.
    local bats_exit_trap
    bats_exit_trap="$(trap -p EXIT)"

    # shellcheck disable=SC1090
    source "$RECOVERY_SH"

    trap - EXIT
    if [[ -n "$bats_exit_trap" ]]; then
        eval "$bats_exit_trap"
    fi
    return 0
}

# Resets the script's module-level state so tests do not leak into one another.
reset_recovery_sh_state() {
    RESULT_NAMES=()
    RESULT_STATES=()
    SUBMITTED_JOB_IDS=()
    CONFIG_KEYS_SET=()
    JOB_ID="test-job"
    WORK_DIR=""
    HANDSHAKE_DIR=""
    VERDICT_DIR=""
    INPUT_DIR=""
    INPUT_FILE=""
    CHECKPOINT_DIR="/tmp/ckpt"
    FLINK_CONF=""
    TM_PID_BEFORE=""
    TM_RESOURCE_ID_BEFORE=""
    RESTORED_BEFORE=""
    RELEASE_DEADLINE_S=""
    CHECKPOINT_INTERVAL_MS="5000"
    RESTART_ATTEMPTS="3"
    STANDALONE_STARTUP_TIME="600s"
    EXPECTED_AGENTS_VERSION="0.3.dev0"
    # Keep the polls short; every wait under test is given a small budget.
    POLL_INTERVAL=1
}

# Replaces the REST transport with a fixed body. Callers set REST_STUB_BODY.
stub_rest_get() {
    REST_STUB_BODY=""
    rest_get() { printf '%s' "$REST_STUB_BODY"; }
}

# json_query reads its body from stdin, and bats's `run` takes a single command
# rather than a pipeline — so these wrap the pipe. Running it through `bash -c`
# instead would lose the sourced functions.
query_fixture() {
    local fixture="$1" mode="$2" path="$3"
    "$fixture" | json_query "$mode" "$path"
}

query_body() {
    local body="$1" mode="$2" path="$3"
    printf '%s' "$body" | json_query "$mode" "$path"
}

# ---------------------------------------------------------------------------
# Response fixtures, shaped after the real REST payloads.
# ---------------------------------------------------------------------------
fixture_checkpoints() {
    cat <<'JSON'
{"counts":{"restored":0,"total":7,"in_progress":1,"completed":6,"failed":0},
 "summary":{},
 "latest":{"completed":{"id":6},"savepoint":null,"failed":null,"restored":null},
 "history":[]}
JSON
}

fixture_checkpoints_restored() {
    cat <<'JSON'
{"counts":{"restored":1,"total":9,"in_progress":0,"completed":8,"failed":0},
 "summary":{},
 "latest":{"completed":{"id":8},"savepoint":null,"failed":null,
           "restored":{"id":6,"restore_timestamp":123,"is_savepoint":false,
                       "external_path":"file:///x"}},
 "history":[]}
JSON
}

fixture_taskmanagers_one() {
    printf '%s' '{"taskmanagers":[{"id":"172.18.0.3:39479-caf7a9","path":"akka://x","dataPort":1}]}'
}

fixture_taskmanagers_none() {
    printf '%s' '{"taskmanagers":[]}'
}

fixture_job_running() {
    printf '%s' '{"jid":"abc","name":"n","state":"RUNNING","vertices":[]}'
}

# GET /jobmanager/config answers with a list of key/value pairs.
fixture_jobmanager_config() {
    cat <<'JSON'
[{"key":"execution.checkpointing.dir","value":"file:///tmp/ckpt"},
 {"key":"restart-strategy.type","value":"fixed-delay"},
 {"key":"restart-strategy.fixed-delay.attempts","value":"3"},
 {"key":"resourcemanager.standalone.start-up-time","value":"600s"}]
JSON
}

# GET /jobs/:jobid/checkpoints/config answers from the job's checkpoint
# coordinator, and reports the interval in milliseconds.
fixture_checkpoint_config() {
    printf '%s' '{"mode":"exactly_once","interval":5000,"timeout":600000,"min_pause":0,"max_concurrent":1,"externalization":{"enabled":false,"delete_on_cancellation":true},"state_backend":"HashMapStateBackend","checkpoint_storage":"FileSystemCheckpointStorage"}'
}

# ---------------------------------------------------------------------------
# A nested Flink 2.x config.yaml, shaped like the shipped file: the keys the
# harness appends have no nested counterpart, and two that do are present so a
# test can show appending did not displace them.
# ---------------------------------------------------------------------------
write_nested_config_fixture() {
    cat > "$1" <<'YAML'
################################################################################
# Sample nested Flink 2.x configuration.
################################################################################

jobmanager:
  rpc:
    address: localhost
    port: 6123
  memory:
    process:
      size: 1600m
  execution:
    failover-strategy: region

taskmanager:
  bind-host: localhost
  host: localhost
  numberOfTaskSlots: 1
  memory:
    process:
      size: 1728m

parallelism:
  default: 1

execution:
  checkpointing:
    incremental: true

rest:
  address: localhost
  bind-address: localhost
YAML
}

# Any top-level (column 0) YAML key appearing more than once. snakeyaml, which
# Flink's config loader uses, rejects a duplicate key outright rather than taking
# the last value, so this is the condition a repeated append has to avoid. Done
# with grep rather than a YAML parser so the suite needs no Python packages.
duplicate_top_level_keys() {
    grep -oE '^[^[:space:]#][^:]*:' "$1" | sort | uniq -d
}

# An interpreter that can import the payload module, or empty if there is none.
# assert_handshake_budget reads the tool's release deadline from that module, so
# without one the test cannot run. The candidates cover the venv the e2e harness
# itself activates, one sitting in the checkout, an activated environment, and
# finally whatever python is on PATH.
find_payload_interpreter() {
    local candidate
    for candidate in "$REPO_ROOT/.flink-agents-env/bin/python" \
                     "$REPO_ROOT/venv/bin/python" \
                     "${VIRTUAL_ENV:-}/bin/python" \
                     "$(command -v python3 || true)" \
                     "$(command -v python || true)"; do
        [[ -n "$candidate" && -x "$candidate" ]] || continue
        if PYTHONPATH="$REPO_ROOT/python" "$candidate" -c \
            'import flink_agents.e2e_tests.e2e_tests_integration.checkpoint_recovery_agent' \
            >/dev/null 2>&1; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

# The Flink config.yaml as shipped, from an installed Flink or from PyFlink's
# bundled copy. Empty when neither is present.
find_shipped_config() {
    local candidate
    for candidate in "${FLINK_HOME:-}/conf/config.yaml" \
                     "$REPO_ROOT"/.flink-agents-env/lib/python*/site-packages/pyflink/conf/config.yaml \
                     "$REPO_ROOT"/venv/lib/python*/site-packages/pyflink/conf/config.yaml \
                     "${VIRTUAL_ENV:-}"/lib/python*/site-packages/pyflink/conf/config.yaml; do
        [[ -f "$candidate" ]] && { printf '%s' "$candidate"; return 0; }
    done
    return 1
}
