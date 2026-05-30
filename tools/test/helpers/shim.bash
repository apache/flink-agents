# PATH-shim helpers for integration tests. Loaded via `load 'helpers/shim'`.
#
# After `shim_setup` runs, $BATS_TEST_TMPDIR/bin is prepended to PATH and
# every shimmed binary appends one tab-separated line per invocation to
# $BATS_TEST_TMPDIR/calls/<name>.log.
#
# `shim_bin_missing` additionally registers a name in SHIM_MISSING_NAMES;
# the `command` function override below intercepts `command -v <name>` for
# those names and returns 1, so install.sh's `command -v curl &>/dev/null`
# checks see them as unavailable.

shim_setup() {
    SHIM_DIR="$BATS_TEST_TMPDIR/bin"
    SHIM_CALLS="$BATS_TEST_TMPDIR/calls"
    SHIM_MISSING_NAMES=()
    mkdir -p "$SHIM_DIR" "$SHIM_CALLS"
    export PATH="$SHIM_DIR:$PATH"
}

# Override `command` as a shell function. When SHIM_MISSING_NAMES is empty
# (i.e. shim_setup hasn't been called or nothing has been marked missing),
# this is fully transparent — every call falls through to `builtin command`.
command() {
    if [[ "$1" == "-v" ]]; then
        local q="$2"
        local m
        for m in "${SHIM_MISSING_NAMES[@]:-}"; do
            if [[ "$q" == "$m" ]]; then
                return 1
            fi
        done
    fi
    builtin command "$@"
}

# Replace `name` with a stub that records argv and exits with `exit_code` (default 0).
shim_bin() {
    local name="$1" exit_code="${2:-0}"
    cat >"$SHIM_DIR/$name" <<EOF
#!/usr/bin/env bash
( IFS=\$'\t'; printf '%s\n' "\$*" ) >> "$SHIM_CALLS/$name.log"
exit $exit_code
EOF
    chmod +x "$SHIM_DIR/$name"
}

# Replace `name` with a stub that records argv then runs an arbitrary shell body.
# Note: `body` must not contain a line that is exactly `EOF`, or it will
# terminate the heredoc prematurely.
shim_bin_script() {
    local name="$1" body="$2"
    cat >"$SHIM_DIR/$name" <<EOF
#!/usr/bin/env bash
( IFS=\$'\t'; printf '%s\n' "\$*" ) >> "$SHIM_CALLS/$name.log"
$body
EOF
    chmod +x "$SHIM_DIR/$name"
}

# Make `name` resolve to "missing" for both `command -v` checks and actual
# invocation: register it in SHIM_MISSING_NAMES (the `command` override
# returns 1 for these) and drop an exit-127 stub on PATH (so direct
# invocation doesn't accidentally hit the real system binary).
shim_bin_missing() {
    local name="$1"
    SHIM_MISSING_NAMES+=("$name")
    cat >"$SHIM_DIR/$name" <<'EOF'
#!/usr/bin/env bash
exit 127
EOF
    chmod +x "$SHIM_DIR/$name"
}

# Print all recorded calls for `name`, one per line, tab-separated argv.
shim_calls() {
    local name="$1"
    cat "$SHIM_CALLS/$name.log" 2>/dev/null || true
}

# Print the number of times `name` was invoked.
shim_call_count() {
    local name="$1"
    if [[ ! -f "$SHIM_CALLS/$name.log" ]]; then
        echo 0
        return
    fi
    wc -l < "$SHIM_CALLS/$name.log" | tr -d ' '
}
