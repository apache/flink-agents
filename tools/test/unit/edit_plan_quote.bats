#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    # The bash 3.x test skips when no such interpreter is present, and sourcing
    # install.sh replaces bats' EXIT trap, which `skip` needs in order to report.
    # That test reaches edit_plan_quote through a child interpreter, so it wants
    # nothing loaded here. Removing the trap instead of leaving it alone does not
    # help; the skip is swallowed either way.
    if [[ "$BATS_TEST_DESCRIPTION" != *"bash 3.x"* ]]; then
        load_install_sh
        reset_install_sh_state
    fi
}

# Probes the usual bash locations and prints the first one whose major version
# is 3, or nothing when none of them qualifies.
find_bash3() {
    local candidate
    for candidate in /bin/bash /usr/bin/bash /usr/local/bin/bash /opt/homebrew/bin/bash; do
        [[ -x "$candidate" ]] || continue
        if [[ "$("$candidate" -c 'printf %s "${BASH_VERSINFO[0]}"' 2>/dev/null)" == "3" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 0
}

# edit_plan_quote single-quotes its argument so the parent shell can
# safely `source` the dumped state file. Round-tripping the output via
# eval should reproduce the original string exactly.

@test "edit_plan_quote: empty string round-trips" {
    local quoted
    quoted="$(edit_plan_quote "")"
    local out
    eval "out=$quoted"
    [ "$out" = "" ]
}

@test "edit_plan_quote: plain path round-trips" {
    local quoted
    quoted="$(edit_plan_quote "/opt/flink")"
    local out
    eval "out=$quoted"
    [ "$out" = "/opt/flink" ]
}

@test "edit_plan_quote: path with spaces round-trips" {
    local quoted
    quoted="$(edit_plan_quote "/home/jin doe/flink")"
    local out
    eval "out=$quoted"
    [ "$out" = "/home/jin doe/flink" ]
}

@test "edit_plan_quote: path with single quote round-trips" {
    local input="/tmp/it's-fine"
    local quoted
    quoted="$(edit_plan_quote "$input")"
    local out
    eval "out=$quoted"
    [ "$out" = "$input" ]
}

# The inline form this replaced is correct on 5.3.15 and broken on 3.2.57, so
# a body that calls edit_plan_quote directly only catches the bug when it
# happens to be running the older one. Invoking a real 3.x explicitly is what
# covers the bash that install.sh's own users run.
#
# The value is adversarial on three axes at once. The adjacent pair of quotes
# defeats an escape that replaces only the first occurrence. The lone quote
# further along defeats an escape that handles only adjacent pairs. The $x
# defeats wrapping the value in double quotes instead of single.
@test "edit_plan_quote: repeated single quotes are escaped identically on bash 3.x" {
    local bash3
    bash3="$(find_bash3)"
    [[ -n "$bash3" ]] || skip "no bash 3.x interpreter found; install one to cover macOS /bin/bash"

    local input="/tmp/o''brien's dir \$x"
    local quoted
    quoted="$(FLINK_AGENTS_INSTALL_SH_NO_RUN=1 "$bash3" -c \
        '. "$1"; edit_plan_quote "$2"' _ \
        "${BATS_TEST_DIRNAME}/../../install.sh" "$input")"

    # Expected bytes: '/tmp/o'\'''\''brien'\''s dir $x'
    [ "$quoted" = "'/tmp/o'\\'''\\''brien'\\''s dir \$x'" ]

    local out
    eval "out=$quoted"
    [ "$out" = "$input" ]
}

@test "edit_plan_quote: shell metacharacters are not expanded on source-back" {
    local input='/tmp/$HOME-or-$(rm -rf /)'
    local quoted
    quoted="$(edit_plan_quote "$input")"
    local out
    eval "out=$quoted"
    [ "$out" = "$input" ]
}

@test "edit_plan_dump_state: writes a sourceable file that restores values" {
    INSTALL_FLINK="No"
    FLINK_VERSION="2.1.1"
    INSTALL_DIR="/tmp/old"
    FLINK_HOME="/usr/local/flink"
    FLINK_MAJOR_MINOR="2.1"
    ENABLE_PYFLINK="Yes"
    PYFLINK_ACTUALLY_ENABLED=1
    VENV_DIR="/tmp/venv with space"
    PYTHON_BIN="/usr/bin/python3"
    FLINK_AGENTS_VERSION="0.2.0"
    RECREATE_VENV_PATH="/tmp/venv with space"

    local f="$BATS_TEST_TMPDIR/state"
    edit_plan_dump_state "$f"

    # Clobber the live values, then re-source.
    INSTALL_FLINK=""; FLINK_VERSION=""; INSTALL_DIR=""; FLINK_HOME=""
    FLINK_MAJOR_MINOR=""; ENABLE_PYFLINK=""; PYFLINK_ACTUALLY_ENABLED=0
    VENV_DIR=""; PYTHON_BIN=""; FLINK_AGENTS_VERSION=""
    RECREATE_VENV_PATH=""

    # shellcheck disable=SC1090
    source "$f"

    [ "$INSTALL_FLINK" = "No" ]
    [ "$FLINK_VERSION" = "2.1.1" ]
    [ "$INSTALL_DIR" = "/tmp/old" ]
    [ "$FLINK_HOME" = "/usr/local/flink" ]
    [ "$FLINK_MAJOR_MINOR" = "2.1" ]
    [ "$ENABLE_PYFLINK" = "Yes" ]
    [ "$PYFLINK_ACTUALLY_ENABLED" = "1" ]
    [ "$VENV_DIR" = "/tmp/venv with space" ]
    [ "$PYTHON_BIN" = "/usr/bin/python3" ]
    [ "$FLINK_AGENTS_VERSION" = "0.2.0" ]
    [ "$RECREATE_VENV_PATH" = "/tmp/venv with space" ]
}
