#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
    OS="linux"
}

@test "show_install_plan: Environment section always present" {
    INSTALL_FLINK=Yes
    INSTALL_DIR="/opt/flink"
    FLINK_HOME="/opt/flink/flink-2.2.0"
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"Environment (read-only)"*) ;; *) false ;; esac
    case "$output" in *"OS:"*) ;; *) false ;; esac
    case "$output" in *"Java:"*) ;; *) false ;; esac
    case "$output" in *"JAVA_HOME:"*) ;; *) false ;; esac
    case "$output" in *"Python:"*) ;; *) false ;; esac
}

@test "show_install_plan: INSTALL_FLINK=Yes shows version + install directory, not FLINK_HOME explicit" {
    INSTALL_FLINK=Yes
    INSTALL_DIR="/opt/flink"
    FLINK_VERSION="2.2.0"
    FLINK_HOME="/opt/flink/flink-2.2.0"
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"Flink version"*) ;; *) false ;; esac
    case "$output" in *"Install directory"*) ;; *) false ;; esac
}

@test "show_install_plan: INSTALL_FLINK=No hides Install directory, shows FLINK_HOME with version" {
    INSTALL_FLINK=No
    INSTALL_DIR="/should/not/show"
    FLINK_VERSION="2.1.1"
    FLINK_HOME="/usr/local/flink"
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"FLINK_HOME"*) ;; *) false ;; esac
    case "$output" in *"v2.1.1"*) ;; *) false ;; esac
    case "$output" in *"Install directory"*) false ;; *) ;; esac
    case "$output" in *"/should/not/show"*) false ;; *) ;; esac
}

@test "show_install_plan: JAVA_HOME unset shows hint" {
    INSTALL_FLINK=Yes
    INSTALL_DIR="/opt/flink"
    FLINK_HOME="/opt/flink/flink-2.2.0"
    unset JAVA_HOME
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"<not set, Flink will auto-detect>"*) ;; *) false ;; esac
}

@test "show_install_plan: Flink Agents version line is present" {
    INSTALL_FLINK=Yes
    INSTALL_DIR="/opt/flink"
    FLINK_HOME="/opt/flink/flink-2.2.0"
    FLINK_AGENTS_VERSION="0.2.1"
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"Flink Agents version"*"0.2.1"*) ;; *) false ;; esac
}

@test "show_install_plan: no redundant 'Install flink-agents JARs' row" {
    INSTALL_FLINK=Yes
    INSTALL_DIR="/opt/flink"
    FLINK_HOME="/opt/flink/flink-2.2.0"
    run show_install_plan
    [ "$status" -eq 0 ]
    case "$output" in *"Install flink-agents JARs"*) false ;; *) ;; esac
}
