#!/usr/bin/env bats
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

setup() {
    source "${BATS_TEST_DIRNAME}/../../../e2e-test/test-scripts/test_submit_examples_to_flink.sh"
    JOB_STATUS_POLL_INTERVAL=1
    TEST_STATE_INDEX=0
    TEST_STATES=()

    sleep() {
        :
    }

    get_job_state() {
        local last_index=$((${#TEST_STATES[@]} - 1))
        local index="$TEST_STATE_INDEX"
        if (( index > last_index )); then
            index="$last_index"
        fi
        JOB_STATE="${TEST_STATES[$index]}"
        TEST_STATE_INDEX=$((TEST_STATE_INDEX + 1))
        return 0
    }
}

set_job_states() {
    TEST_STATES=("$@")
    TEST_STATE_INDEX=0
}

stub_main_dependencies() {
    install_flink() { :; }
    build_project() { :; }
    locate_examples_jar() { :; }
    stage_dist_jars() { :; }
    start_ollama() { :; }
    start_cluster() { :; }
    discover_java_examples() { printf 'org.example.JavaExample\n'; }
    discover_python_quickstart_examples() { printf '/tmp/python_example.py\n'; }
    discover_python_rag_examples() { printf '/tmp/rag_example.py\n'; }
    setup_rag_knowledge_base() { :; }
    submit_java_example() { :; }
    submit_python_example() { :; }
    submit_python_rag_example() { :; }
}

create_fake_executable() {
    local path="$1"
    mkdir -p "$(dirname "$path")"
    printf '#!/usr/bin/env bash\nexit 0\n' > "$path"
    chmod +x "$path"
}

@test "job state parser extracts the current state" {
    run extract_job_state '{"jid":"job-id","name":"example","state":"RUNNING"}'

    [ "$status" -eq 0 ]
    [ "$output" = "RUNNING" ]
}

@test "job state parser rejects a response without state" {
    run extract_job_state '{"errors":["job not found"]}'

    [ "$status" -ne 0 ]
    [ -z "$output" ]
}

@test "available slot parser extracts the current count" {
    run extract_available_slots '{"taskmanagers":1,"slots-total":1,"slots-available":1}'

    [ "$status" -eq 0 ]
    [ "$output" = "1" ]
}

@test "slot release check waits until a slot becomes available" {
    local slot_index=0
    get_available_slots() {
        local slots=(0 0 1)
        AVAILABLE_SLOTS="${slots[$slot_index]}"
        if (( slot_index < 2 )); then
            slot_index=$((slot_index + 1))
        fi
        return 0
    }

    run wait_for_cluster_slot_available "example" 4

    [ "$status" -eq 0 ]
}

@test "job health check accepts FINISHED immediately" {
    set_job_states FINISHED

    run wait_for_job_healthy "job-id" "example" 5 2

    [ "$status" -eq 0 ]
    [[ "$output" == *"reached FINISHED status"* ]] || false
}

@test "job health check accepts a continuously RUNNING job" {
    set_job_states CREATED RUNNING RUNNING RUNNING

    run wait_for_job_healthy "job-id" "example" 5 2

    [ "$status" -eq 0 ]
    [[ "$output" == *"remained RUNNING for 2s"* ]] || false
}

@test "job health check rejects a failing job" {
    set_job_states CREATED RUNNING FAILING
    check_logs_for_errors() {
        return 0
    }

    run wait_for_job_healthy "job-id" "example" 5 2

    [ "$status" -ne 0 ]
    [[ "$output" == *"entered unexpected state: FAILING"* ]] || false
}

@test "job health check resets the stability period after a restart" {
    set_job_states RUNNING RESTARTING RUNNING RUNNING

    run wait_for_job_healthy "job-id" "example" 4 2

    [ "$status" -ne 0 ]
    [[ "$output" == *"did not become stably RUNNING or FINISHED"* ]] || false
}

@test "submitted job is marked failed when health verification fails" {
    RESULT_NAMES=()
    RESULT_STATES=()
    wait_for_job_healthy() {
        return 1
    }
    cancel_job_after_check() {
        return 0
    }

    verify_submitted_job "job-id" "example"

    [ "${RESULT_NAMES[0]}" = "example" ]
    [ "${RESULT_STATES[0]}" = "FAIL" ]
}

@test "submitted job passes only after health verification and cleanup" {
    RESULT_NAMES=()
    RESULT_STATES=()
    CANCEL_CALLED=0
    wait_for_job_healthy() {
        return 0
    }
    cancel_job_after_check() {
        CANCEL_CALLED=1
        return 0
    }

    verify_submitted_job "job-id" "example"

    [ "$CANCEL_CALLED" -eq 1 ]
    [ "${RESULT_NAMES[0]}" = "example" ]
    [ "${RESULT_STATES[0]}" = "PASS" ]
}

@test "submitted job is marked failed when cleanup fails" {
    RESULT_NAMES=()
    RESULT_STATES=()
    wait_for_job_healthy() {
        return 0
    }
    cancel_job_after_check() {
        return 1
    }

    verify_submitted_job "job-id" "example"

    [ "${RESULT_STATES[0]}" = "FAIL" ]
}

@test "cancel race succeeds when a finished job has already released its slot" {
    FLINK_HOME="$BATS_TEST_TMPDIR/flink"
    local state_calls=0
    get_job_state() {
        state_calls=$((state_calls + 1))
        if (( state_calls == 1 )); then
            JOB_STATE="RUNNING"
            return 0
        fi
        return 1
    }
    timeout() {
        return 1
    }
    ensure_cluster_slot_available() {
        return 0
    }

    run cancel_job_after_check "job-id" "example"

    [ "$status" -eq 0 ]
    [[ "$output" == *"no longer occupies the cluster slot"* ]] || false
}

@test "slow slot cleanup restarts the standalone cluster" {
    local restarted=0
    wait_for_cluster_slot_available() {
        return 1
    }
    restart_cluster_after_cleanup() {
        restarted=1
        return 0
    }

    ensure_cluster_slot_available "example"

    [ "$restarted" -eq 1 ]
}

@test "cleanup fails when neither cancellation nor cluster recovery succeeds" {
    FLINK_HOME="$BATS_TEST_TMPDIR/flink"
    get_job_state() {
        JOB_STATE="RUNNING"
        return 0
    }
    timeout() {
        return 1
    }
    ensure_cluster_slot_available() {
        return 1
    }

    run cancel_job_after_check "job-id" "example"

    [ "$status" -ne 0 ]
    [[ "$output" == *"failed to cancel job job-id"* ]] || false
}

@test "CI chat model aliases cover every hardcoded quickstart model" {
    [[ " ${OLLAMA_CHAT_MODEL_ALIASES[*]} " == *" qwen3:1.7b "* ]] || false
    [[ " ${OLLAMA_CHAT_MODEL_ALIASES[*]} " == *" qwen3:8b "* ]] || false
    [[ " ${OLLAMA_CHAT_MODEL_ALIASES[*]} " == *" qwen3.5:9b "* ]] || false
}

@test "Java submission keeps the attached client alive during validation" {
    unset -f sleep

    local fake_flink_home="$BATS_TEST_TMPDIR/flink"
    local client_marker="$BATS_TEST_TMPDIR/client-running"
    local client_release="$BATS_TEST_TMPDIR/client-release"
    mkdir -p "$fake_flink_home/bin"
    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'touch "$ATTACHED_CLIENT_MARKER"' \
        'printf "Job has been submitted with JobID 0123456789abcdef0123456789abcdef\n"' \
        'while [[ ! -f "$ATTACHED_CLIENT_RELEASE" ]]; do sleep 0.1; done' \
        > "$fake_flink_home/bin/flink"
    chmod +x "$fake_flink_home/bin/flink"

    FLINK_HOME="$fake_flink_home"
    EXAMPLES_JAR="$BATS_TEST_TMPDIR/examples.jar"
    ATTACHED_CLIENT_MARKER="$client_marker"
    ATTACHED_CLIENT_RELEASE="$client_release"
    export ATTACHED_CLIENT_MARKER ATTACHED_CLIENT_RELEASE
    SUBMIT_TIMEOUT=5
    RESULT_NAMES=()
    RESULT_STATES=()
    SUBMITTED_JOB_IDS=()
    SUBMISSION_PIDS=()

    timeout() {
        shift
        "$@"
    }
    wait_for_job_healthy() {
        [[ -f "$client_marker" ]]
    }
    cancel_job_after_check() {
        touch "$client_release"
    }

    submit_java_example "org.example.JavaExample"

    [ "${RESULT_NAMES[0]}" = "java:JavaExample" ]
    [ "${RESULT_STATES[0]}" = "PASS" ]
    [ "${SUBMITTED_JOB_IDS[0]}" = "0123456789abcdef0123456789abcdef" ]
    [ "${#SUBMISSION_PIDS[@]}" -eq 0 ]
}

@test "main propagates Java example discovery failure" {
    stub_main_dependencies
    discover_java_examples() {
        return 1
    }

    run main

    [ "$status" -ne 0 ]
    [[ "$output" == *"Java example discovery failed"* ]] || false
}

@test "main propagates Python quickstart discovery failure" {
    stub_main_dependencies
    discover_python_quickstart_examples() {
        return 1
    }

    run main

    [ "$status" -ne 0 ]
    [[ "$output" == *"Python quickstart example discovery failed"* ]] || false
}

@test "main propagates RAG example discovery failure" {
    stub_main_dependencies
    discover_python_rag_examples() {
        return 1
    }

    run main

    [ "$status" -ne 0 ]
    [[ "$output" == *"Python RAG example discovery failed"* ]] || false
}

@test "main propagates RAG knowledge base setup failure" {
    stub_main_dependencies
    setup_rag_knowledge_base() {
        return 1
    }

    run main

    [ "$status" -ne 0 ]
    [[ "$output" == *"Cannot run Python RAG examples because setup failed"* ]] || false
}

@test "main skips RAG setup when no RAG examples exist" {
    stub_main_dependencies
    discover_python_rag_examples() {
        return 0
    }
    setup_rag_knowledge_base() {
        return 1
    }

    run main

    [ "$status" -eq 0 ]
}

@test "Flink bootstrap sources installer helpers without installing released Agents" {
    ROOT_DIR="$BATS_TEST_TMPDIR/root"
    BOOTSTRAP_CALLS="$BATS_TEST_TMPDIR/bootstrap-calls"
    export BOOTSTRAP_CALLS
    mkdir -p "$ROOT_DIR/tools"
    printf '%s\n' \
        'plan_flink() {' \
        '  printf "plan:%s:%s:%s\\n" "$FLINK_VERSION" "$INSTALL_FLINK" "$INSTALL_DIR" >> "$BOOTSTRAP_CALLS"' \
        '}' \
        'install_flink_if_needed() {' \
        '  printf "install-flink\\n" >> "$BOOTSTRAP_CALLS"' \
        '}' \
        'install_flink_agents_jar() {' \
        '  printf "install-agents\\n" >> "$BOOTSTRAP_CALLS"' \
        '  return 1' \
        '}' \
        > "$ROOT_DIR/tools/install.sh"

    install_flink_distribution "$BATS_TEST_TMPDIR/install"

    [ "$(sed -n '1p' "$BOOTSTRAP_CALLS")" = \
        "plan:$FLINK_VERSION:Yes:$BATS_TEST_TMPDIR/install" ]
    [ "$(sed -n '2p' "$BOOTSTRAP_CALLS")" = "install-flink" ]
    [ "$(wc -l < "$BOOTSTRAP_CALLS" | tr -d ' ')" -eq 2 ]
}

@test "reused FLINK_HOME prepares PyFlink without installing released Agents artifacts" {
    local fake_flink_home="$BATS_TEST_TMPDIR/flink"
    local fake_venv="$BATS_TEST_TMPDIR/venv"
    create_fake_executable "$fake_flink_home/bin/flink"
    mkdir -p "$fake_flink_home/lib" "$fake_flink_home/opt"
    : > "$fake_flink_home/opt/flink-python-${FLINK_VERSION}.jar"

    FLINK_HOME="$fake_flink_home"
    VENV_DIR="$fake_venv"
    PREPARE_VENV_CALLED=0
    INSTALL_DISTRIBUTION_CALLED=0
    prepare_python_venv() {
        PREPARE_VENV_CALLED=1
    }
    install_flink_distribution() {
        INSTALL_DISTRIBUTION_CALLED=1
        return 1
    }

    install_flink

    [ "$INSTALL_DISTRIBUTION_CALLED" -eq 0 ]
    [ "$PREPARE_VENV_CALLED" -eq 1 ]
    [ -f "$fake_flink_home/lib/flink-python-${FLINK_VERSION}.jar" ]
}

@test "fresh setup invokes only the Flink distribution installer" {
    local install_dir="$BATS_TEST_TMPDIR/install"
    local fake_flink_home="$install_dir/flink-$FLINK_VERSION"
    local fake_venv="$BATS_TEST_TMPDIR/venv"

    unset FLINK_HOME
    INSTALL_DIR="$install_dir"
    VENV_DIR="$fake_venv"
    INSTALL_DISTRIBUTION_CALLED=0
    prepare_python_venv() {
        :
    }
    install_flink_distribution() {
        INSTALL_DISTRIBUTION_CALLED=1
        [ "$1" = "$install_dir" ]
        create_fake_executable "$fake_flink_home/bin/flink"
        mkdir -p "$fake_flink_home/lib" "$fake_flink_home/opt"
        : > "$fake_flink_home/opt/flink-python-${FLINK_VERSION}.jar"
    }

    install_flink

    [ "$FLINK_HOME" = "$fake_flink_home" ]
    [ "$INSTALL_DISTRIBUTION_CALLED" -eq 1 ]
    [ -f "$fake_flink_home/lib/flink-python-${FLINK_VERSION}.jar" ]
}

@test "built wheel and matching PyFlink are installed into the E2E venv" {
    ROOT_DIR="$BATS_TEST_TMPDIR/root"
    VENV_DIR="$BATS_TEST_TMPDIR/venv"
    PYTHON_CALLS="$BATS_TEST_TMPDIR/python-calls"
    export PYTHON_CALLS
    mkdir -p "$ROOT_DIR/python/dist" "$VENV_DIR/bin"
    : > "$ROOT_DIR/python/dist/flink_agents-0.3.0-py3-none-any.whl"
    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'printf "%s\\n" "$*" >> "$PYTHON_CALLS"' \
        'exit 0' \
        > "$VENV_DIR/bin/python"
    chmod +x "$VENV_DIR/bin/python"
    printf 'TEST_VENV_ACTIVATED=1\n' > "$VENV_DIR/bin/activate"

    install_built_python_package

    [ "$TEST_VENV_ACTIVATED" -eq 1 ]
    [ "$PYFLINK_CLIENT_EXECUTABLE" = "$VENV_DIR/bin/python" ]
    [[ "$(cat "$PYTHON_CALLS")" == *"-m pip install --quiet"* ]] || false
    [[ "$(cat "$PYTHON_CALLS")" == *"apache-flink==$FLINK_VERSION"* ]] || false
}
