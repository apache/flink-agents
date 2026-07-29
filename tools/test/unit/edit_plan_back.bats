#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

# When a sub-prompt's ESC propagates `exit 130` out of edit_plan_interactive's
# subshell, the surrounding `set -e` MUST NOT kill the installer. The wrapper
# should swallow the non-zero exit and return 0 ("back to confirm").

@test "edit_plan_interactive: subshell exit 130 is treated as back, not as installer kill" {
    # Replace edit_plan_interactive with a stand-in that runs a subshell
    # which exits 130 the same way the real ESC path does, and uses the
    # exact `|| rc=$?` pattern we ship.
    fake_edit() {
        local rc=0
        (
            exit 130
        ) || rc=$?
        if (( rc != 0 )); then
            return 0
        fi
        return 99   # we should never get here
    }

    # If `set -e` were still propagating the 130, the test process would
    # die here. Reaching the assertion means the pattern works.
    run fake_edit
    [ "$status" -eq 0 ]
}

@test "edit_plan_interactive: subshell exit 1 (gum ESC) also becomes back" {
    fake_edit() {
        local rc=0
        ( exit 1 ) || rc=$?
        if (( rc != 0 )); then
            return 0
        fi
        return 99
    }
    run fake_edit
    [ "$status" -eq 0 ]
}

@test "edit_plan_interactive: subshell exit 0 sources state and propagates changes" {
    local state_file="$BATS_TEST_TMPDIR/state"
    cat > "$state_file" <<EOF
INSTALL_FLINK='Yes'
FLINK_VERSION='9.9.9'
EOF

    fake_edit() {
        local rc=0
        ( : ) || rc=$?   # subshell that succeeds
        if (( rc != 0 )); then
            return 0
        fi
        # shellcheck disable=SC1090
        source "$state_file"
    }

    INSTALL_FLINK="No"
    FLINK_VERSION="0.0.0"
    fake_edit
    [ "$INSTALL_FLINK" = "Yes" ]
    [ "$FLINK_VERSION" = "9.9.9" ]
}
