#!/usr/bin/env bats

# run.sh puts a `bash` symlink to its own interpreter at the front of PATH, so
# every `#!/usr/bin/env bash` hop inside the run resolves to the shell whose
# version the suite's assertions depend on. That pin reaches the scripts under
# test as well, which is more than it was meant to do: those scripts run on
# whatever interpreter their own users have. FLINK_AGENTS_SUT_BASH separates the
# two, and every site that spawns a script under test names it instead of a bare
# `bash`. An absolute path is not a PATH lookup, which is what lets it out from
# under the pin.
#
# A test cannot observe the interpreter another test's subject ran on, so the
# first test drives a nested bats run over a real file from the suite and reads
# back what that run reached. The other two are static: they are what keeps a
# site added later from quietly going back to the pinned interpreter.

setup() {
    SUITE_ROOT="$BATS_TEST_DIRNAME/.."
    # The smallest file that spawns a script under test, and the only one that
    # needs no helpers, so a nested run of it isolates the spawn.
    HELP_BATS="$SUITE_ROOT/integration/help.bats"
}

# An interpreter that records having been reached and then hands its arguments
# to the shell running this file. Standing in for a second real bash keeps the
# assertion independent of which interpreters the machine happens to carry.
write_recording_interpreter() {
    local dir="$1"
    mkdir -p "$dir"
    cat > "$dir/bash" <<EOF
#!/usr/bin/env bash
printf 'reached\n' >> "$dir/reached"
exec "$BASH" "\$@"
EOF
    chmod +x "$dir/bash"
    printf '%s' "$dir/bash"
}

fail_with_tap() {
    printf 'nested TAP stream:\n%s\n' "$output" >&2
    false
}

@test "spawn site: a converted file spawns its subject on FLINK_AGENTS_SUT_BASH" {
    local dir="$BATS_TEST_TMPDIR/sut"
    local interpreter
    interpreter="$(write_recording_interpreter "$dir")"

    # Read off the file rather than written here: a test added to help.bats
    # later moves the expectation with it, and a spawn that stops naming the
    # interpreter still counts, so reverting one is what the difference reports.
    local spawns
    spawns="$(grep -cE '^[[:space:]]*run .*install\.sh' "$HELP_BATS")"

    run env FLINK_AGENTS_SUT_BASH="$interpreter" \
        "$BATS_ROOT/bin/bats" --formatter tap "$HELP_BATS"

    [ "$status" -eq 0 ] || fail_with_tap
    [ -f "$dir/reached" ] || fail_with_tap
    # Every spawn arrived at the named interpreter, not just one of them.
    [ "$(wc -l < "$dir/reached")" -eq "$spawns" ] || fail_with_tap
}

@test "spawn site: no test file spawns a script under test on the pinned interpreter" {
    # Two ways a spawn ends up back on the harness's shell. A bare `bash` is a
    # PATH lookup, which the pin claims. Handing the script's own path to `run`
    # relies on its `#!/usr/bin/env bash`, which is the same lookup one step
    # later. Either way the subject runs on the interpreter the assertions need
    # rather than the one its users have, and nothing reports it.
    #
    # Both patterns read one line at a time, so a spawn split across a line
    # continuation is outside what they can see; recognising that needs context
    # a line-oriented rule does not have.
    #
    # Two bare spawns are legitimate and are recognised by what the line names
    # rather than by where it sits, so neither rots when a file is edited:
    #
    #   - a line naming FLINK_AGENTS_SUT_BASH: the bare `bash -c` there owns a
    #     redirection that `run` cannot carry, and the subject on that line is
    #     spawned by the inner, named interpreter.
    #   - a line naming FLINK_AGENTS_RECOVERY_SH_NO_RUN: it sources the
    #     checkpoint-recovery script, which CI exercises on ubuntu only, so the
    #     interpreter this variable selects is not one that subject ever meets.
    #
    # The second pattern names the three scripts under test and the variables
    # that hold their paths, rather than any path at all: a rule wide enough to
    # cover every conceivable spelling would fire on the fixtures and stubs
    # these files write, and a check that cries wolf gets deleted.
    local bare='(^|[^-[:alnum:]_/$"])bash[[:space:]]+("?\$|-c )'
    local direct='(^|[[:space:]])run (env ([A-Za-z_][A-Za-z0-9_]*=("[^"]*"|[^ "]*) )+)?'
    direct+='"(\$\{?(UT_SH|BUILD_SCRIPT|INSTALL_SH)\}?|[^"]*/(install|ut|build)\.sh)"'

    local offenders
    offenders="$( { grep -rnE "$bare" \
                        "$SUITE_ROOT/unit" "$SUITE_ROOT/integration" --include='*.bats' \
                        | grep -v -e 'FLINK_AGENTS_SUT_BASH' -e 'FLINK_AGENTS_RECOVERY_SH_NO_RUN'
                    grep -rnE "$direct" \
                        "$SUITE_ROOT/unit" "$SUITE_ROOT/integration" --include='*.bats'
                  } | sort -u || true)"
    if [[ -n "$offenders" ]]; then
        printf 'spawn sites not naming the subject interpreter:\n%s\n' "$offenders" >&2
        false
    fi
}

@test "spawn site: every one falls back to the harness's \`bash\`" {
    # The fallback has to be the bare word, so an unset variable keeps the
    # behaviour the suite had before it existed, and it has to keep the colon,
    # so a caller that exports the variable empty does not leave the site
    # naming nothing. A fixed path here would move every subject off the
    # harness silently.
    #
    # This file names the variable in prose and drives it in the nested run
    # above, so it is excluded by name rather than by path: grep reports the
    # path it walked, which is not the one BATS_TEST_FILENAME holds.
    local wrong
    wrong="$(grep -rn 'FLINK_AGENTS_SUT_BASH' \
        "$SUITE_ROOT/unit" "$SUITE_ROOT/integration" --include='*.bats' \
        | grep -v "/$(basename "$BATS_TEST_FILENAME"):" \
        | grep -v '\${FLINK_AGENTS_SUT_BASH:-bash}' \
        || true)"
    if [[ -n "$wrong" ]]; then
        printf 'spawn sites not using ${FLINK_AGENTS_SUT_BASH:-bash}:\n%s\n' "$wrong" >&2
        false
    fi
}
