#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
    FLINK_HOME="$BATS_TEST_TMPDIR/flink-home"
    mkdir -p "$FLINK_HOME/bin" "$FLINK_HOME/lib"
}

@test "detect_flink_version_from_home: prefers bin/flink --version when runnable" {
    cat > "$FLINK_HOME/bin/flink" <<'EOF'
#!/usr/bin/env bash
echo "Version: 2.1.1, Commit ID: abc"
EOF
    chmod +x "$FLINK_HOME/bin/flink"
    FLINK_VERSION=""
    run detect_flink_version_from_home
    [ "$status" -eq 0 ]
    [ "$FLINK_VERSION" = "2.1.1" ] || {
        # `run` executes in subshell — re-run inline to read the assignment.
        FLINK_VERSION=""
        detect_flink_version_from_home
        [ "$FLINK_VERSION" = "2.1.1" ]
    }
}

@test "detect_flink_version_from_home: falls back to lib/flink-dist-*.jar" {
    : > "$FLINK_HOME/lib/flink-dist-2.0.1.jar"
    FLINK_VERSION=""
    detect_flink_version_from_home
    [ "$FLINK_VERSION" = "2.0.1" ]
}

@test "detect_flink_version_from_home: returns non-zero when no source available" {
    FLINK_VERSION=""
    run detect_flink_version_from_home
    [ "$status" -ne 0 ]
}

@test "detect_flink_version_from_home: ignores stderr noise from flink --version" {
    cat > "$FLINK_HOME/bin/flink" <<'EOF'
#!/usr/bin/env bash
echo "WARNING: some noise" >&2
echo "Version: 1.20.3, Commit ID: deadbeef"
EOF
    chmod +x "$FLINK_HOME/bin/flink"
    FLINK_VERSION=""
    detect_flink_version_from_home
    [ "$FLINK_VERSION" = "1.20.3" ]
}

@test "detect_flink_version_from_home: jar fallback picks first match deterministically" {
    : > "$FLINK_HOME/lib/flink-dist-2.2.0.jar"
    : > "$FLINK_HOME/lib/flink-dist-other-1.0.jar"
    FLINK_VERSION=""
    detect_flink_version_from_home
    [ "$FLINK_VERSION" = "2.2.0" ]
}

@test "detect_flink_version_from_home: announces progress before invoking bin/flink" {
    # No dist jar in lib/ -> forces the slow path that runs bin/flink --version.
    cat > "$FLINK_HOME/bin/flink" <<'EOF'
#!/usr/bin/env bash
echo "Version: 2.1.1, Commit ID: abc"
EOF
    chmod +x "$FLINK_HOME/bin/flink"
    FLINK_VERSION=""
    run detect_flink_version_from_home
    [ "$status" -eq 0 ]
    case "$output" in *"Detecting Flink version"*) ;; *) false ;; esac
}

@test "detect_flink_version_from_home: fast jar path does NOT announce progress" {
    : > "$FLINK_HOME/lib/flink-dist-2.0.1.jar"
    FLINK_VERSION=""
    run detect_flink_version_from_home
    [ "$status" -eq 0 ]
    case "$output" in *"Detecting Flink version"*) false ;; *) ;; esac
}
