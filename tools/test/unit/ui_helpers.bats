#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
    # Force the non-gum fallback branch.
    GUM=""
}

@test "ui_info: prints the message in fallback branch" {
    run ui_info "hello world"
    [ "$status" -eq 0 ]
    [[ "$output" == *"hello world"* ]]
}

@test "ui_warn: prints the message in fallback branch" {
    run ui_warn "be careful"
    [ "$status" -eq 0 ]
    [[ "$output" == *"be careful"* ]]
}

@test "ui_success: prints the message in fallback branch" {
    run ui_success "all good"
    [ "$status" -eq 0 ]
    [[ "$output" == *"all good"* ]]
}

@test "ui_error: prints the message in fallback branch" {
    run ui_error "uh oh"
    [ "$status" -eq 0 ]
    [[ "$output" == *"uh oh"* ]]
}

@test "ui_kv: prints key and value" {
    run ui_kv "Flink version" "2.2.0"
    [ "$status" -eq 0 ]
    [[ "$output" == *"Flink version"* ]]
    [[ "$output" == *"2.2.0"* ]]
}

@test "ui_stage: increments stage counter and prints title" {
    INSTALL_STAGE_CURRENT=0
    run ui_stage "First thing"
    [ "$status" -eq 0 ]
    [[ "$output" == *"First thing"* ]]
    [[ "$output" == *"[1/${INSTALL_STAGE_TOTAL}]"* ]]
}

@test "die: prints message and exits non-zero" {
    run die "fatal boom"
    [ "$status" -ne 0 ]
    [[ "$output" == *"fatal boom"* ]]
}
