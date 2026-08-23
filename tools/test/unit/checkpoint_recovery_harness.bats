#!/usr/bin/env bats

# Unit tests for the pure functions in
# e2e-test/test-scripts/test_checkpoint_recovery.sh.
#
# That script's job is to decide whether Python agent memory survived a
# TaskManager kill, and almost all of it needs a real cluster. These tests cover
# the parts that do not: JSON extraction, the comparison operator, the wait
# helpers' failure reporting, the idempotent config edit, and the three
# assertions that turn observations into a pass or a fail.
#
# The emphasis is on failure reporting rather than happy paths. A recovery test
# that cannot distinguish "the condition was false" from "I was looking in the
# wrong place" reports absence when it means ignorance, and that is precisely how
# a test passes while verifying nothing.

# Needed for `run --separate-stderr`, used where a helper returns a value on stdout
# and logs on stderr. tools/test/run.sh pins bats to v1.11.0.
bats_require_minimum_version 1.5.0

setup() {
    load '../helpers/recovery'
    load_recovery_sh
    reset_recovery_sh_state
}

# ---------------------------------------------------------------------------
# json_query — reading values
# ---------------------------------------------------------------------------

@test "json_query: int reads a nested count" {
    run query_fixture fixture_checkpoints int counts.completed
    [ "$status" -eq 0 ]
    [ "$output" = "6" ]
}

@test "json_query: str reads a job state" {
    run query_fixture fixture_job_running str state
    [ "$status" -eq 0 ]
    [ "$output" = "RUNNING" ]
}

@test "json_query: len counts a list" {
    run query_fixture fixture_taskmanagers_one len taskmanagers
    [ "$status" -eq 0 ]
    [ "$output" = "1" ]
}

@test "json_query: len of an empty list is 0, not a read failure" {
    run query_fixture fixture_taskmanagers_none len taskmanagers
    [ "$status" -eq 0 ]
    [ "$output" = "0" ]
}

@test "json_query: a list index walks into the list" {
    run query_fixture fixture_taskmanagers_one str taskmanagers.0.id
    [ "$status" -eq 0 ]
    [ "$output" = "172.18.0.3:39479-caf7a9" ]
}

@test "json_query: nullable distinguishes a null leaf from a populated one" {
    run query_fixture fixture_checkpoints nullable latest.restored
    [ "$status" -eq 0 ]
    [ "$output" = "null" ]

    run query_fixture fixture_checkpoints_restored nullable latest.restored
    [ "$status" -eq 0 ]
    [ "$output" = "present" ]
}

@test "json_query: conf finds a key whose name contains dots" {
    run query_fixture fixture_jobmanager_config conf restart-strategy.type
    [ "$status" -eq 0 ]
    [ "$output" = "fixed-delay" ]
}

@test "json_query: interval comes back as exact milliseconds" {
    run query_fixture fixture_checkpoint_config int interval
    [ "$status" -eq 0 ]
    [ "$output" = "5000" ]
}

# ---------------------------------------------------------------------------
# json_query — read failures. Every one of these must exit non-zero and print
# nothing, so that "I could not read this" can never be mistaken for a value.
# ---------------------------------------------------------------------------

@test "json_query: an empty body is a read failure" {
    run query_body '' int counts.completed
    [ "$status" -eq 1 ]
    [ "$output" = "" ]
}

@test "json_query: a whitespace-only body is a read failure" {
    run query_body '   ' int counts.completed
    [ "$status" -eq 1 ]
    [ "$output" = "" ]
}

@test "json_query: malformed JSON is a read failure" {
    run query_body '{"counts":' int counts.completed
    [ "$status" -eq 1 ]
    [ "$output" = "" ]
}

@test "json_query: an HTML error page is a read failure" {
    run query_body '<html>404</html>' int counts.completed
    [ "$status" -eq 1 ]
    [ "$output" = "" ]
}

@test "json_query: a missing leaf is a read failure" {
    run query_fixture fixture_checkpoints int counts.nope
    [ "$status" -eq 1 ]
}

@test "json_query: a misspelled nullable leaf is a read failure, not a null" {
    # Otherwise a typo would read as "no restore yet" forever and the wait would
    # blame the condition instead of the field name.
    run query_fixture fixture_checkpoints nullable latest.restoredx
    [ "$status" -eq 1 ]
}

@test "json_query: a leaf of the wrong type is a read failure" {
    run query_fixture fixture_checkpoints str counts.completed
    [ "$status" -eq 1 ]

    run query_fixture fixture_checkpoints len counts
    [ "$status" -eq 1 ]

    run query_body '{"a":true}' int a
    [ "$status" -eq 1 ]
}

@test "json_query: an absent conf key is a read failure" {
    run query_fixture fixture_jobmanager_config conf nosuch.key
    [ "$status" -eq 1 ]
}

@test "json_query: a list index past the end is a read failure" {
    run query_fixture fixture_taskmanagers_none str taskmanagers.0.id
    [ "$status" -eq 1 ]
}

# ---------------------------------------------------------------------------
# value_matches
# ---------------------------------------------------------------------------

@test "value_matches: eq compares strings" {
    run value_matches "RUNNING" "eq:RUNNING"
    [ "$status" -eq 0 ]
    run value_matches "CREATED" "eq:RUNNING"
    [ "$status" -eq 1 ]
}

@test "value_matches: ge is inclusive" {
    run value_matches "3" "ge:3"
    [ "$status" -eq 0 ]
    run value_matches "4" "ge:3"
    [ "$status" -eq 0 ]
    run value_matches "2" "ge:3"
    [ "$status" -eq 1 ]
}

@test "value_matches: nonnull keys off the nullable marker" {
    run value_matches "present" "nonnull"
    [ "$status" -eq 0 ]
    run value_matches "null" "nonnull"
    [ "$status" -eq 1 ]
}

@test "value_matches: a non-numeric ge operand is refused, not evaluated" {
    # (( )) would treat RUNNING as a variable name and abort the script under
    # set -u. Status 2 means "cannot compare", which the caller separates from
    # "does not match".
    run value_matches "RUNNING" "ge:3"
    [ "$status" -eq 2 ]

    run value_matches "3" "ge:oops"
    [ "$status" -eq 2 ]
}

@test "value_matches: an unknown operator is refused" {
    run value_matches "3" "gt:2"
    [ "$status" -eq 2 ]
    run value_matches "3" "lt:2"
    [ "$status" -eq 2 ]
}

# ---------------------------------------------------------------------------
# The REST transport itself. Every read the harness performs goes through
# rest_get, and nothing else in this suite looks at the request layer: rest_get
# is replaced by stub_rest_get everywhere it is exercised.
# ---------------------------------------------------------------------------

@test "REST reads: every curl in test_checkpoint_recovery.sh is bounded by --max-time" {
    # Contract: no request may run without a deadline of its own. A peer that
    # accepts the connection and then never answers is not covered by curl's
    # connect timeout, so an unbounded read has nothing to end it and outlives the
    # wait loop that issued it.
    #
    # This reads the script's text rather than its behavior, which makes it
    # deliberately brittle: the -m short form, a curl inside a heredoc, or a
    # request split across a line continuation would fail it and need it
    # rewritten — the continuation because --max-time on the next line is never
    # seen. That cost is accepted because the invariant has no behavioral surface
    # here — every caller stubs the transport out — and an unbounded read
    # reintroduced anywhere in the file would otherwise be invisible until a run
    # hung.
    run grep -nE '^[[:space:]]*[^#]*curl[[:space:]]' "$RECOVERY_SH"
    # A denominator: a rename or a refactor that leaves no curl at all must fail
    # here rather than sweep nothing and read as a pass.
    [ "$status" -eq 0 ]

    local line
    while IFS= read -r line; do
        case "$line" in
            *--max-time*) continue ;;
            *) printf 'unbounded curl: %s\n' "$line"; return 1 ;;
        esac
    done <<< "$output"
}

# ---------------------------------------------------------------------------
# wait_for_rest — the four states of (probe parsed, target parsed). Each must
# report a different thing, because each means something different.
# ---------------------------------------------------------------------------

@test "wait_for_rest: a satisfied condition returns 0" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoints)"
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completed" "ge:6" 3
    [ "$status" -eq 0 ]
}

@test "wait_for_rest: neither field readable blames the endpoint" {
    stub_rest_get
    REST_STUB_BODY=""
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completed" "ge:99" 3
    [ "$status" -eq 1 ]
    [[ "$output" == *"never parsed"* ]] || false
    [[ "$output" == *"NOT evidence"* ]] || false
}

@test "wait_for_rest: probe readable but target not blames the field name" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoints)"
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completedx" "ge:1" 3
    [ "$status" -eq 1 ]
    [[ "$output" == *"so the endpoint is right"* ]] || false
    [[ "$output" == *"never parsed 'counts.completedx'"* ]] || false
    [[ "$output" == *"NOT evidence"* ]] || false
}

@test "wait_for_rest: a genuinely false condition reports the observed value" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoints)"
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completed" "ge:99" 3
    [ "$status" -eq 1 ]
    [[ "$output" == *"was last '6'"* ]] || false
    # A condition that really was evaluated and really was false must not carry
    # the disclaimer, or the disclaimer stops meaning anything.
    [[ "$output" != *"NOT evidence"* ]] || false
}

@test "wait_for_rest: target observed but probe missing still reports the value" {
    # The fourth state. Reporting only "the probe never parsed" here would
    # withhold the one number a reader needs, and would claim the condition was
    # not evaluated when it was.
    stub_rest_get
    REST_STUB_BODY='{"counts":{"completed":6}}'
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completed" "ge:99" 3
    [ "$status" -eq 1 ]
    [[ "$output" == *"was last '6'"* ]] || false
    [[ "$output" != *"NOT evidence"* ]] || false
    # ...while still saying the response was not fully as expected.
    [[ "$output" == *"only partly as expected"* ]] || false
}

@test "wait_for_rest: an uncomparable value stops the wait instead of spinning" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_job_running)"
    run wait_for_rest "stub" "/stub" "str:state" "str:state" "ge:3" 6
    [ "$status" -eq 1 ]
    [[ "$output" == *"cannot be evaluated"* ]] || false
}

@test "wait_for_rest: a slow endpoint does not multiply the budget" {
    # Counting only the sleeps ignores what each iteration actually costs — the
    # request plus two python3 spawns — so against a slow endpoint a nominal
    # budget used to overrun several times over, which would blow through the
    # tool's release deadline before the harness noticed.
    #
    # The 2s delay in the stub is what makes this test discriminate: measured
    # against a zero-latency stub, a deadline and an accumulated count both finish
    # a 4s budget in 4s, and the test would pass either way.
    REST_STUB_BODY="$(fixture_checkpoints)"
    rest_get() { sleep 2; printf '%s' "$REST_STUB_BODY"; }
    local start=$SECONDS
    run wait_for_rest "stub" "/stub" "int:counts.total" "int:counts.completed" "ge:99" 4
    local elapsed=$((SECONDS - start))
    [ "$status" -eq 1 ]
    # A deadline stops at about 6s here. Accumulating sleeps would have run four
    # iterations of roughly 3s each, so about 12s.
    [ "$elapsed" -lt 9 ]
}

# ---------------------------------------------------------------------------
# wait_for_file — every file the job publishes is written to "<name>.tmp" and
# renamed, so a crash between the two leaves a permanent twin.
# ---------------------------------------------------------------------------

@test "wait_for_file: an existing file returns 0" {
    : > "$BATS_TEST_TMPDIR/verdict.json"
    run wait_for_file "stub" "$BATS_TEST_TMPDIR/verdict.json" 2
    [ "$status" -eq 0 ]
}

@test "wait_for_file: an absent file times out without inventing a twin" {
    run wait_for_file "stub" "$BATS_TEST_TMPDIR/verdict.json" 2
    [ "$status" -eq 1 ]
    [[ "$output" == *"did not appear"* ]] || false
    [[ "$output" != *"leftover"* ]] || false
}

@test "wait_for_file: a leftover .tmp twin is reported and never accepted" {
    : > "$BATS_TEST_TMPDIR/verdict.json.tmp"
    run wait_for_file "stub" "$BATS_TEST_TMPDIR/verdict.json" 2
    [ "$status" -eq 1 ]
    [[ "$output" == *"leftover"* ]] || false
    [[ "$output" == *"verdict.json.tmp"* ]] || false
}

# ---------------------------------------------------------------------------
# Environment setup. The harness assembles the Python environment and the cluster
# classpath itself rather than delegating to tools/install.sh, because install.sh
# would populate both from a *released* Flink Agents. These cover the choices that
# assembly makes: which directory and interpreter become the venv, when an existing
# one is reused instead, what an absent build artifact does, and which dist jars the
# cluster is left holding.
# ---------------------------------------------------------------------------

@test "prepare_python_venv: an existing venv is reused, not recreated" {
    VENV_DIR="$BATS_TEST_TMPDIR/venv"
    mkdir -p "$VENV_DIR/bin"
    : > "$VENV_DIR/pyvenv.cfg"
    printf '#!/bin/sh\n' > "$VENV_DIR/bin/python"
    chmod +x "$VENV_DIR/bin/python"
    # Recreating would run this and fail, so a pass means it was not attempted.
    PYTHON_BIN=false

    run prepare_python_venv
    [ "$status" -eq 0 ]
    [[ "$output" == *"Reusing Python venv"* ]] || false
}

@test "prepare_python_venv: a non-empty directory that is not a venv is refused" {
    VENV_DIR="$BATS_TEST_TMPDIR/occupied"
    mkdir -p "$VENV_DIR"
    : > "$VENV_DIR/unrelated-file"
    PYTHON_BIN=false

    run prepare_python_venv
    [ "$status" -eq 1 ]
    [[ "$output" == *"not an empty directory or a valid venv"* ]] || false
}

@test "prepare_python_venv: creating one targets VENV_DIR with the configured interpreter" {
    VENV_DIR="$BATS_TEST_TMPDIR/fresh"
    local recorder="$BATS_TEST_TMPDIR/recording-python"
    cat > "$recorder" <<EOF
#!/bin/sh
printf '%s\n' "\$*" > "$BATS_TEST_TMPDIR/venv-args"
EOF
    chmod +x "$recorder"
    PYTHON_BIN="$recorder"

    run prepare_python_venv
    [ "$status" -eq 0 ]
    [ "$(cat "$BATS_TEST_TMPDIR/venv-args")" = "-m venv $VENV_DIR" ]
}

@test "install_built_python_package: an absent wheel is a failure, never a fallback" {
    ROOT_DIR="$BATS_TEST_TMPDIR/repo"
    mkdir -p "$ROOT_DIR/python/dist"

    run install_built_python_package
    [ "$status" -eq 1 ]
    [[ "$output" == *"Python wheel not found"* ]] || false
}

# A jar carrying a different version has a different filename, so copying the
# built one cannot replace it — both would end up on the classpath.
@test "stage_dist_jars: a dist jar of another version is removed, not left beside the built one" {
    ROOT_DIR="$BATS_TEST_TMPDIR/repo"
    FLINK_HOME="$BATS_TEST_TMPDIR/flink"
    FLINK_MAJOR_MINOR="2.3"
    mkdir -p "$ROOT_DIR/dist/flink-2.3/target" "$FLINK_HOME/lib"
    printf '<project>\n<version>ignored-parent</version>\n<version>0.3-SNAPSHOT</version>\n</project>\n' \
        > "$ROOT_DIR/pom.xml"
    : > "$ROOT_DIR/dist/flink-2.3/target/flink-agents-dist-flink-2.3-0.3-SNAPSHOT.jar"
    : > "$FLINK_HOME/lib/flink-agents-dist-flink-2.3-0.3.0.jar"

    stage_dist_jars

    local jars=("$FLINK_HOME/lib"/flink-agents-dist-*.jar)
    [ "${#jars[@]}" -eq 1 ]
    [ "$(basename "${jars[0]}")" = "flink-agents-dist-flink-2.3-0.3-SNAPSHOT.jar" ]
}

# ---------------------------------------------------------------------------
# The trigger file. The job's source stays open for the life of the job, so it
# needs a file to read and the harness supplies it. Both silent failure modes are
# covered here: a file the source's filter skips, and an empty file, either of
# which leaves the job running and reading nothing until a later wait expires.
#
# The `|| false` after each [[ ]] is load-bearing, not decoration. errexit in bash
# 3.2, the interpreter macOS supplies and bats takes test bodies from, does not
# apply to [[ ]], so a bare [[ ]] assertion cannot fail a test there — it only
# fails from bash 4.1 on. Chaining a simple command onto it restores the check.
# ---------------------------------------------------------------------------

@test "prepare_work_dirs: writes a trigger file the source will read" {
    TMPDIR="$BATS_TEST_TMPDIR"

    prepare_work_dirs

    [ -s "$INPUT_FILE" ]
    # Inside the work directory, so cleanup removes it and two runs cannot share one.
    [[ "$INPUT_FILE" == "$WORK_DIR"/* ]] || false
    # Flink's default file filter skips these prefixes even for a path handed to the
    # source directly, and skipping is silent.
    [[ "$(basename "$INPUT_FILE")" != [._]* ]] || false
}

@test "prepare_work_dirs: an empty trigger file stops the run instead of reaching the job" {
    TMPDIR="$BATS_TEST_TMPDIR"
    # Land the rename on an empty file, which is the state the guard exists for.
    mv() { : > "$2"; }

    run prepare_work_dirs
    [ "$status" -eq 1 ]
    [[ "$output" == *"missing or empty"* ]] || false
}

# Covers the argument submit_job builds, and the file state at that boundary given a
# caller that ran prepare_work_dirs first. It does not establish that main() calls them
# in that order; the test below does that.
@test "submit_job: the job is handed a trigger file that already exists and is non-empty" {
    TMPDIR="$BATS_TEST_TMPDIR"
    ROOT_DIR="$BATS_TEST_TMPDIR/repo"
    VENV_DIR="$BATS_TEST_TMPDIR/venv"
    FLINK_HOME="$BATS_TEST_TMPDIR/flink"
    local state="$BATS_TEST_TMPDIR/input-file-state"
    local fake_bin="$BATS_TEST_TMPDIR/bin"
    mkdir -p "$ROOT_DIR/python/$(dirname "$JOB_MODULE")" \
             "$VENV_DIR/bin" "$FLINK_HOME/bin" "$fake_bin"
    : > "$ROOT_DIR/python/$JOB_MODULE"
    printf '#!/bin/sh\necho purelib\n' > "$VENV_DIR/bin/python"
    # macOS ships no timeout(1), and the harness calls it unqualified.
    printf '#!/bin/sh\nshift\nexec "$@"\n' > "$fake_bin/timeout"

    # Stands in for the Flink CLI, and reports the state of the file it was pointed
    # at as of the moment of submission. -f as well as -s: a directory is non-empty
    # by -s, so without it any path-shaped argument would satisfy the check.
    cat > "$FLINK_HOME/bin/flink" <<EOF
#!/bin/sh
prev=""
for arg in "\$@"; do
    if [ "\$prev" = "--input-file" ]; then
        if [ -f "\$arg" ] && [ -s "\$arg" ]; then
            echo non-empty-file > "$state"
        else
            echo "not-a-non-empty-file: \$arg" > "$state"
        fi
    fi
    prev="\$arg"
done
[ -f "$state" ] || echo no-input-file-argument > "$state"
echo "Job has been submitted with JobID 0123456789abcdef0123456789abcdef"
EOF
    chmod +x "$VENV_DIR/bin/python" "$fake_bin/timeout" "$FLINK_HOME/bin/flink"
    PATH="$fake_bin:$PATH"

    prepare_work_dirs
    run submit_job

    [ "$status" -eq 0 ]
    [ "$(cat "$state")" = "non-empty-file" ]
}

# The source enumerates its splits eagerly, at job start, so a trigger file written
# after submission is a race, and losing that order is this commit's one hard failure.
# Every step main() drives needs a cluster and is stubbed out; prepare_work_dirs is
# left real, so what submit_job sees is the file the harness actually produced.
@test "main: the trigger file is written before the job is submitted" {
    TMPDIR="$BATS_TEST_TMPDIR"
    local observed="$BATS_TEST_TMPDIR/state-at-submit"
    local step
    for step in install_flink build_project stage_dist_jars configure_flink \
                start_cluster assert_effective_config assert_handshake_budget \
                wait_job_running assert_checkpointing_enabled assert_runtime_identity \
                wait_tool_parked wait_for_checkpoint_containing_payload \
                record_taskmanager_identity kill_taskmanager restart_taskmanager \
                wait_for_restore release_tool assert_verdict; do
        eval "$step() { :; }"
    done
    submit_job() {
        if [ -f "$INPUT_FILE" ] && [ -s "$INPUT_FILE" ]; then
            echo non-empty-file > "$observed"
        else
            echo "not-a-non-empty-file: '$INPUT_FILE'" > "$observed"
        fi
    }

    main

    [ "$(cat "$observed")" = "non-empty-file" ]
}

# ---------------------------------------------------------------------------
# The config edit. A repeated append is a hard YAML duplicate-key failure that
# stops the cluster from starting, and install.sh reuses an extracted Flink home,
# so this fires on the second local run rather than in CI.
# ---------------------------------------------------------------------------

@test "configure_flink: applying twice leaves each key exactly once" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"

    configure_flink
    local after_first
    after_first=$(wc -l < "$FLINK_CONF")
    configure_flink
    [ "$(wc -l < "$FLINK_CONF")" -eq "$after_first" ]

    local key
    for key in execution.checkpointing.interval execution.checkpointing.dir \
               restart-strategy.type restart-strategy.fixed-delay.attempts \
               resourcemanager.standalone.start-up-time; do
        [ "$(grep -c "^${key}: " "$FLINK_CONF")" -eq 1 ]
    done
}

@test "configure_flink: applying twice introduces no duplicate top-level key" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"
    configure_flink
    configure_flink
    run duplicate_top_level_keys "$FLINK_CONF"
    [ "$output" = "" ]
}

@test "configure_flink: the interval is written in the unit the assertion reads" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"
    CHECKPOINT_INTERVAL_MS=7000
    configure_flink
    run grep '^execution.checkpointing.interval: ' "$FLINK_CONF"
    [ "$status" -eq 0 ]
    [ "$output" = "execution.checkpointing.interval: 7000ms" ]
}

@test "delete_config_key: removes the appended lines and nothing else" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"
    cp "$FLINK_CONF" "$BATS_TEST_TMPDIR/config.yaml.orig"

    configure_flink
    local key
    for key in "${CONFIG_KEYS_SET[@]}"; do
        delete_config_key "$key"
    done

    run diff "$BATS_TEST_TMPDIR/config.yaml.orig" "$FLINK_CONF"
    [ "$status" -eq 0 ]
}

@test "configure_flink: appending does not displace a shipped nested value" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"
    configure_flink
    # The nested blocks the appended flat keys sit next to must survive verbatim,
    # indentation included.
    run grep -c '^  numberOfTaskSlots: 1$' "$FLINK_CONF"
    [ "$output" = "1" ]
    run grep -c '^    address: localhost$' "$FLINK_CONF"
    [ "$output" = "1" ]
    run grep -c '^  address: localhost$' "$FLINK_CONF"
    [ "$output" = "1" ]
}

@test "configure_flink: idempotent against the real shipped config.yaml" {
    local shipped
    shipped="$(find_shipped_config || true)"
    [[ -n "$shipped" ]] || skip "no shipped Flink config.yaml found; install Flink or PyFlink to cover this"

    FLINK_CONF="$BATS_TEST_TMPDIR/shipped.yaml"
    cp "$shipped" "$FLINK_CONF"
    cp "$shipped" "$BATS_TEST_TMPDIR/shipped.orig"

    configure_flink
    configure_flink
    run duplicate_top_level_keys "$FLINK_CONF"
    [ "$output" = "" ]

    local key
    for key in "${CONFIG_KEYS_SET[@]}"; do
        delete_config_key "$key"
    done
    run diff "$BATS_TEST_TMPDIR/shipped.orig" "$FLINK_CONF"
    [ "$status" -eq 0 ]
}

# stat spells a file mode differently in its BSD and GNU flavors, and these tests
# run under both.
file_mode() {
    stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

@test "restore_flink_conf: the installation's config comes back byte for byte and mode for mode" {
    FLINK_CONF="$BATS_TEST_TMPDIR/config.yaml"
    write_nested_config_fixture "$FLINK_CONF"
    # A value the installation itself set, at column 0, for a key this run also
    # writes. set_config_key drops that line before appending its own, so nothing
    # narrower than the whole file can put it back.
    printf 'restart-strategy.type: exponential-delay\n' >> "$FLINK_CONF"
    chmod 644 "$FLINK_CONF"
    cp -p "$FLINK_CONF" "$BATS_TEST_TMPDIR/config.yaml.orig"

    backup_flink_conf
    configure_flink

    # Without these two the test could pass against a run that never altered the
    # file, proving nothing about the restore.
    run grep -c '^restart-strategy.type: exponential-delay$' "$FLINK_CONF"
    [ "$output" = "0" ]
    # The mode half of that denominator: the 644 check after the restore is vacuous
    # unless the mode moves off it first.
    [ "$(file_mode "$FLINK_CONF")" = "600" ]

    restore_flink_conf
    run diff "$BATS_TEST_TMPDIR/config.yaml.orig" "$FLINK_CONF"
    [ "$status" -eq 0 ]
    [ "$(file_mode "$FLINK_CONF")" = "644" ]
    [ ! -f "$FLINK_CONF_BACKUP" ]

    # Restoring a second time finds no copy and returns without touching anything.
    # The status is what distinguishes that from a failed copy: cp cannot truncate
    # the destination when its source is missing, so the file is unchanged either
    # way and comparing it again would assert nothing.
    run restore_flink_conf
    [ "$status" -eq 0 ]
}

@test "install_flink: takes the pre-run copy of config.yaml" {
    # The wiring rather than the function. restore_flink_conf can only put the file
    # back if the copy was taken, and the test above calls backup_flink_conf itself
    # — so dropping the one call inside install_flink leaves the suite green. Read
    # from the parsed function body because install_flink needs a real Flink
    # distribution to run: this pins the call, not where it sits in the body.
    run declare -f install_flink
    [ "$status" -eq 0 ]
    printf '%s\n' "$output" | grep -q 'backup_flink_conf'
}

# ---------------------------------------------------------------------------
# assert_effective_config — proves the append reached the configuration the
# JobManager loaded. It cannot prove the option took effect, because
# /jobmanager/config echoes unrecognized keys too; that is what
# assert_checkpointing_enabled is for.
# ---------------------------------------------------------------------------

@test "assert_effective_config: a complete configuration passes" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_jobmanager_config)"
    assert_effective_config
    [ "${RESULT_STATES[0]}" = "PASS" ]
}

@test "assert_effective_config: a missing key fails and is recorded" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_jobmanager_config | sed '/restart-strategy.type/d')"
    run assert_effective_config
    [ "$status" -eq 1 ]
    [[ "$output" == *"absent from the cluster configuration"* ]] || false

    # Recorded, not just reported: the recorded FAIL is what makes the script
    # exit non-zero. `run` uses a subshell, so assert again in this one.
    REST_STUB_BODY="$(fixture_jobmanager_config | sed '/restart-strategy.type/d')"
    assert_effective_config || true
    [ "${RESULT_STATES[0]}" = "FAIL" ]
}

@test "assert_effective_config: a wrong value reports both sides" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_jobmanager_config | sed 's/"fixed-delay"/"disable"/')"
    run assert_effective_config
    [ "$status" -eq 1 ]
    [[ "$output" == *"restart-strategy.type is 'disable', expected 'fixed-delay'"* ]] || false
}

@test "assert_effective_config: an unreachable endpoint fails" {
    stub_rest_get
    REST_STUB_BODY=""
    run assert_effective_config
    [ "$status" -eq 1 ]
    [[ "$output" == *"Could not read"* ]] || false
}

# ---------------------------------------------------------------------------
# assert_checkpointing_enabled — the authoritative guard on the single
# misconfiguration that would let the whole test pass having verified nothing.
# ---------------------------------------------------------------------------

@test "assert_checkpointing_enabled: the expected interval passes" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoint_config)"
    assert_checkpointing_enabled
    [ "${RESULT_STATES[0]}" = "PASS" ]
}

@test "assert_checkpointing_enabled: a 404 means checkpointing is off" {
    # curl -fsS fails on 404, so rest_get yields nothing. This endpoint exists
    # only when the job has checkpointing, which is why absence is conclusive.
    stub_rest_get
    REST_STUB_BODY=""
    run assert_checkpointing_enabled
    [ "$status" -eq 1 ]
    [[ "$output" == *"checkpointing is off"* ]] || false
}

@test "assert_checkpointing_enabled: a different interval fails" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoint_config | sed 's/"interval":5000/"interval":180000/')"
    run assert_checkpointing_enabled
    [ "$status" -eq 1 ]
    [[ "$output" == *"180000ms, expected 5000ms"* ]] || false
}

@test "assert_checkpointing_enabled: overlapping checkpoints warn about the gate" {
    stub_rest_get
    REST_STUB_BODY="$(fixture_checkpoint_config | sed 's/"max_concurrent":1/"max_concurrent":3/')"
    run assert_checkpointing_enabled
    [ "$status" -eq 0 ]
    [[ "$output" == *"weaker than intended"* ]] || false
}

# ---------------------------------------------------------------------------
# assert_verdict — the job publishes one "verdict" field. The harness reads it
# and must not recompute the pass condition from the component booleans.
# ---------------------------------------------------------------------------

verdict_setup() {
    VERDICT_DIR="$BATS_TEST_TMPDIR/verdict"
    mkdir -p "$VERDICT_DIR"
    VERDICT_TIMEOUT=2
    job_state() { printf 'RUNNING'; }
}

@test "assert_verdict: a pass verdict passes" {
    verdict_setup
    printf '%s' '{"verdict":"pass","blob_observed_type":"bytes"}' > "$VERDICT_DIR/verdict.json"
    assert_verdict
    [ "${RESULT_STATES[0]}" = "PASS" ]
}

@test "assert_verdict: a missing verdict file is a failure, never a pass" {
    verdict_setup
    run assert_verdict
    [ "$status" -eq 1 ]
    [[ "$output" == *"nothing was verified"* ]] || false
}

@test "assert_verdict: a fail verdict fails and is recorded" {
    verdict_setup
    printf '%s' '{"verdict":"fail","blob_observed_type":"NoneType"}' > "$VERDICT_DIR/verdict.json"
    run assert_verdict
    [ "$status" -eq 1 ]
    [[ "$output" == *"the job reported 'fail'"* ]] || false

    assert_verdict || true
    [ "${RESULT_STATES[0]}" = "FAIL" ]
}

@test "assert_verdict: the verdict field wins over the component booleans" {
    # Every component says pass; the verdict says fail. The harness must not
    # rebuild the conjunction, or the pass condition would live in two places.
    verdict_setup
    printf '%s' '{"verdict":"fail","restored_blob":true,"restored_context":true,"handshake_ok":true}' \
        > "$VERDICT_DIR/verdict.json"
    run assert_verdict
    [ "$status" -eq 1 ]
}

@test "assert_verdict: a file without a verdict field is a format mismatch" {
    verdict_setup
    printf '%s' '{"restored_blob":true}' > "$VERDICT_DIR/verdict.json"
    run assert_verdict
    [ "$status" -eq 1 ]
    [[ "$output" == *"disagree about the verdict format"* ]] || false
}

@test "assert_verdict: an unparsable verdict file fails" {
    verdict_setup
    printf '%s' 'not json at all' > "$VERDICT_DIR/verdict.json"
    run assert_verdict
    [ "$status" -eq 1 ]
}

@test "assert_verdict: a .tmp twin is never read as the verdict" {
    verdict_setup
    printf '%s' '{"verdict":"pass"}' > "$VERDICT_DIR/verdict.json.tmp"
    run assert_verdict
    [ "$status" -eq 1 ]
    [[ "$output" == *"leftover"* ]] || false
}

@test "assert_verdict: a job that died without a verdict fails fast" {
    verdict_setup
    VERDICT_TIMEOUT=30
    job_state() { printf 'FAILED'; }
    local start=$SECONDS
    run assert_verdict
    local elapsed=$((SECONDS - start))
    [ "$status" -eq 1 ]
    [ "$elapsed" -lt 10 ]
}

# ---------------------------------------------------------------------------
# The tool releases itself after a fixed deadline whether or not the harness has
# finished, so everything between parking the tool and creating `release` has to
# fit inside it — in wall clock, not in a sum of nominal timeouts.
#
# charged_timeout holds the guarantee by measuring; assert_handshake_budget is a
# conservative pre-flight prediction that fails an unusable configuration fast.
# ---------------------------------------------------------------------------

payload_budget_setup() {
    VENV_DIR="$(dirname "$(dirname "$(find_payload_interpreter || true)")")"
    [[ -x "$VENV_DIR/bin/python" ]] || skip "no interpreter can import the payload module"
    ROOT_DIR="$REPO_ROOT"
}

@test "assert_handshake_budget: the shipped defaults fit in wall clock" {
    payload_budget_setup
    run assert_handshake_budget
    [ "$status" -eq 0 ]
    # The deadline is read from the payload module rather than restated here.
    [[ "$output" == *"240s"* ]] || false
}

@test "assert_handshake_budget: a budget that fits nominally but not in wall clock is rejected" {
    # These four were the shipped defaults until the budget was made to bound wall
    # clock: they sum to 195s, comfortably under the 240s deadline, so summing
    # nominal timeouts accepted them. Charged properly — each wait can overrun by a
    # poll interval plus a request timeout, and the unattributed costs are real —
    # they need 268s against 225s available, and must be rejected.
    payload_budget_setup
    CHECKPOINT_TIMEOUT=45 TM_GONE_TIMEOUT=60 TM_UP_TIMEOUT=45 RESTORE_TIMEOUT=45
    run assert_handshake_budget
    [ "$status" -eq 1 ]
    [[ "$output" == *"could take up to"* ]] || false
    [[ "$output" == *"exceeds"* ]] || false
}

@test "assert_handshake_budget: a grossly inflated budget is rejected" {
    payload_budget_setup
    CHECKPOINT_TIMEOUT=200 TM_GONE_TIMEOUT=60 TM_UP_TIMEOUT=45 RESTORE_TIMEOUT=45
    run assert_handshake_budget
    [ "$status" -eq 1 ]
}

@test "assert_handshake_budget: a slower request timeout can make a budget infeasible" {
    # CURL_MAX_TIME is charged per wait, so raising it eats the slack. This is the
    # coupling that a nominal sum cannot see at all.
    payload_budget_setup
    run assert_handshake_budget
    [ "$status" -eq 0 ]
    CURL_MAX_TIME=30
    run assert_handshake_budget
    [ "$status" -eq 1 ]
}

@test "charged_timeout: passes a configured budget through when time remains" {
    RELEASE_DEADLINE_S=240
    HANDSHAKE_DEADLINE_AT=$((SECONDS + 100))
    run --separate-stderr charged_timeout "step" 30
    [ "$status" -eq 0 ]
    [ "$output" = "30" ]
}

@test "charged_timeout: clamps to the time actually left" {
    # The measured bound. Whatever earlier steps spent — modelled or not — has
    # already gone, so this step may only have what is left.
    RELEASE_DEADLINE_S=240
    HANDSHAKE_DEADLINE_AT=$((SECONDS + 7))
    # --separate-stderr because the clamp logs a warning while returning the number.
    run --separate-stderr charged_timeout "step" 30
    [ "$status" -eq 0 ]
    [ "$output" -le 7 ]
    [ "$output" -gt 0 ]
}

@test "charged_timeout: fails once the deadline has passed" {
    RELEASE_DEADLINE_S=240
    HANDSHAKE_DEADLINE_AT=$((SECONDS - 1))
    run charged_timeout "step" 30
    [ "$status" -eq 1 ]
    [[ "$output" == *"elapsed before this step began"* ]] || false
}

@test "handshake_budget_left: never reports a negative remainder" {
    HANDSHAKE_DEADLINE_AT=$((SECONDS - 60))
    run --separate-stderr handshake_budget_left
    [ "$output" = "0" ]
}

# ---------------------------------------------------------------------------
# cleanup's work-directory decision. The directory holds the verdict, the
# handshake markers and the checkpoints, so it must survive anything that needs
# explaining. Exercised in a subshell because the decision runs from the EXIT
# trap and depends on the exit status.
# ---------------------------------------------------------------------------

run_cleanup_with_exit() {  # $1 = exit code, $2 = recorded state ("" for none),
                           # $3 = FLINK_CONF, $4 = FLINK_CONF_BACKUP (both
                           # optional; empty leaves the restore inert)
    local code="$1" state="$2" conf="${3:-}" backup="${4:-}"
    env FLINK_AGENTS_RECOVERY_SH_NO_RUN=1 bash -c '
        source "$1"
        WORK_DIR="$2"; mkdir -p "$WORK_DIR"
        FLINK_CONF="$5"
        FLINK_CONF_BACKUP="$6"
        if [[ -n "$4" ]]; then RESULT_NAMES=(step); RESULT_STATES=("$4"); fi
        exit "$3"
    ' _ "$RECOVERY_SH" "$BATS_TEST_TMPDIR/wd" "$code" "$state" "$conf" "$backup" \
        >/dev/null 2>&1 || true
}

@test "cleanup: a clean exit with everything passing removes the work directory" {
    run_cleanup_with_exit 0 PASS
    [ ! -d "$BATS_TEST_TMPDIR/wd" ]
}

@test "cleanup: a recorded FAIL keeps the work directory" {
    run_cleanup_with_exit 0 FAIL
    [ -d "$BATS_TEST_TMPDIR/wd" ]
}

@test "cleanup: a non-zero exit keeps it even with no FAIL recorded" {
    # The setup steps and several assertions abort under set -e without recording
    # anything, so judging by the recorded results alone would delete the
    # diagnostics for exactly the runs that need them.
    run_cleanup_with_exit 3 PASS
    [ -d "$BATS_TEST_TMPDIR/wd" ]
}

@test "cleanup: recording nothing at all keeps the work directory" {
    run_cleanup_with_exit 0 ""
    [ -d "$BATS_TEST_TMPDIR/wd" ]
}

@test "cleanup: the EXIT trap puts the installation's config.yaml back" {
    # The other half of the wiring: a restore nothing calls leaves the installation
    # mutated, and a test that calls restore_flink_conf directly cannot see that.
    write_nested_config_fixture "$BATS_TEST_TMPDIR/config.yaml"
    cp -p "$BATS_TEST_TMPDIR/config.yaml" "$BATS_TEST_TMPDIR/config.yaml.orig"
    cp -p "$BATS_TEST_TMPDIR/config.yaml" "$BATS_TEST_TMPDIR/config.yaml.bak"
    printf 'execution.checkpointing.interval: 5000ms\n' >> "$BATS_TEST_TMPDIR/config.yaml"

    run_cleanup_with_exit 0 PASS \
        "$BATS_TEST_TMPDIR/config.yaml" "$BATS_TEST_TMPDIR/config.yaml.bak"

    run diff "$BATS_TEST_TMPDIR/config.yaml.orig" "$BATS_TEST_TMPDIR/config.yaml"
    [ "$status" -eq 0 ]
}

# ---------------------------------------------------------------------------
# print_summary
# ---------------------------------------------------------------------------

@test "print_summary: recording nothing exits non-zero" {
    # A run that recorded no assertion verified nothing. Reporting that as
    # success is the failure this script exists to prevent.
    RESULT_NAMES=()
    RESULT_STATES=()
    run print_summary
    [ "$status" -eq 1 ]
    [[ "$output" == *"nothing was verified"* ]] || false
}

@test "print_summary: any recorded FAIL exits non-zero" {
    RESULT_NAMES=("a" "b")
    RESULT_STATES=("PASS" "FAIL")
    run print_summary
    [ "$status" -eq 1 ]
}

@test "print_summary: all passing returns 0" {
    RESULT_NAMES=("a" "b")
    RESULT_STATES=("PASS" "PASS")
    run print_summary
    [ "$status" -eq 0 ]
}
