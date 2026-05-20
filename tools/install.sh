#!/usr/bin/env bash
################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

set -euo pipefail

BOLD='\033[1m'
ACCENT='\033[38;2;255;77;77m'       # coral-bright
INFO='\033[38;2;136;146;176m'       # text-secondary
SUCCESS='\033[38;2;0;229;204m'      # cyan-bright
WARN='\033[38;2;255;176;32m'        # amber
ERROR='\033[38;2;230;57;70m'        # coral-mid
MUTED='\033[38;2;90;100;128m'       # text-muted
NC='\033[0m' # No Color

TMPFILES=()
cleanup_tmpfiles() {
    local f
    for f in "${TMPFILES[@]:-}"; do
        rm -rf "$f" 2>/dev/null || true
    done
}
trap cleanup_tmpfiles EXIT

mktempfile() {
    local f
    f="$(mktemp)"
    TMPFILES+=("$f")
    echo "$f"
}

DOWNLOADER=""
detect_downloader() {
    if command -v curl &> /dev/null; then
        DOWNLOADER="curl"
        return 0
    fi
    if command -v wget &> /dev/null; then
        DOWNLOADER="wget"
        return 0
    fi
    ui_error "Missing downloader (curl or wget required)"
    exit 1
}

download_file() {
    local url="$1"
    local output="$2"
    if [[ -z "$DOWNLOADER" ]]; then
        detect_downloader
    fi
    if [[ "$DOWNLOADER" == "curl" ]]; then
        curl -fL --progress-bar --proto '=https' --tlsv1.2 --retry 3 --max-time 600 --retry-delay 1 --retry-connrefused -o "$output" "$url"
        return
    fi
    wget -q --show-progress --https-only --secure-protocol=TLSv1_2 --tries=3 --timeout=600 -O "$output" "$url"
}

GUM_VERSION="${FLINK_AGENTS_GUM_VERSION:-0.17.0}"
GUM=""
GUM_STATUS="skipped"
GUM_REASON=""

is_non_interactive_shell() {
    if [[ "${NO_PROMPT:-0}" == "1" ]]; then
        return 0
    fi
    if [[ ! -t 0 || ! -t 1 ]]; then
        return 0
    fi
    return 1
}

gum_is_tty() {
    if [[ -n "${NO_COLOR:-}" ]]; then
        return 1
    fi
    if [[ "${TERM:-dumb}" == "dumb" ]]; then
        return 1
    fi
    if [[ -t 2 || -t 1 ]]; then
        return 0
    fi
    if [[ -r /dev/tty && -w /dev/tty ]]; then
        return 0
    fi
    return 1
}

gum_detect_os() {
    case "$(uname -s 2>/dev/null || true)" in
        Darwin) echo "Darwin" ;;
        Linux) echo "Linux" ;;
        *) echo "unsupported" ;;
    esac
}

gum_detect_arch() {
    case "$(uname -m 2>/dev/null || true)" in
        x86_64|amd64) echo "x86_64" ;;
        arm64|aarch64) echo "arm64" ;;
        i386|i686) echo "i386" ;;
        armv7l|armv7) echo "armv7" ;;
        armv6l|armv6) echo "armv6" ;;
        *) echo "unknown" ;;
    esac
}

verify_sha256sum_file() {
    local checksums="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum --ignore-missing -c "$checksums" >/dev/null 2>&1
        return $?
    fi
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 --ignore-missing -c "$checksums" >/dev/null 2>&1
        return $?
    fi
    return 1
}

bootstrap_gum_temp() {
    GUM=""
    GUM_STATUS="skipped"
    GUM_REASON=""

    if is_non_interactive_shell; then
        GUM_REASON="non-interactive shell (auto-disabled)"
        return 1
    fi

    if ! gum_is_tty; then
        GUM_REASON="terminal does not support gum UI"
        return 1
    fi

    if command -v gum >/dev/null 2>&1; then
        GUM="gum"
        GUM_STATUS="found"
        GUM_REASON="already installed"
        return 0
    fi

    if ! command -v tar >/dev/null 2>&1; then
        GUM_REASON="tar not found"
        return 1
    fi

    local os arch asset base gum_tmpdir gum_path
    os="$(gum_detect_os)"
    arch="$(gum_detect_arch)"
    if [[ "$os" == "unsupported" || "$arch" == "unknown" ]]; then
        GUM_REASON="unsupported os/arch ($os/$arch)"
        return 1
    fi

    asset="gum_${GUM_VERSION}_${os}_${arch}.tar.gz"
    base="https://github.com/charmbracelet/gum/releases/download/v${GUM_VERSION}"

    gum_tmpdir="$(mktemp -d)"
    TMPFILES+=("$gum_tmpdir")

    ui_info "Installing gum v${GUM_VERSION}, please wait..."

    if ! download_file "${base}/${asset}" "$gum_tmpdir/$asset"; then
        GUM_REASON="download failed"
        return 1
    fi

    if ! download_file "${base}/checksums.txt" "$gum_tmpdir/checksums.txt"; then
        GUM_REASON="checksum unavailable or failed"
        return 1
    fi

    if ! (cd "$gum_tmpdir" && verify_sha256sum_file "checksums.txt"); then
        GUM_REASON="checksum unavailable or failed"
        return 1
    fi

    if ! tar -xzf "$gum_tmpdir/$asset" -C "$gum_tmpdir" >/dev/null 2>&1; then
        GUM_REASON="extract failed"
        return 1
    fi

    gum_path="$(find "$gum_tmpdir" -type f -name gum 2>/dev/null | head -n1 || true)"
    if [[ -z "$gum_path" ]]; then
        GUM_REASON="gum binary missing after extract"
        return 1
    fi

    chmod +x "$gum_path" >/dev/null 2>&1 || true
    if [[ ! -x "$gum_path" ]]; then
        GUM_REASON="gum binary is not executable"
        return 1
    fi

    GUM="$gum_path"
    GUM_STATUS="installed"
    GUM_REASON="temp, verified"
    return 0
}

print_gum_status() {
    case "$GUM_STATUS" in
        found)
            ui_success "gum available (${GUM_REASON})"
            ;;
        installed)
            ui_success "gum bootstrapped (${GUM_REASON}, v${GUM_VERSION})"
            ;;
        *)
            if [[ -n "$GUM_REASON" && "$GUM_REASON" != "non-interactive shell (auto-disabled)" ]]; then
                ui_info "gum skipped (${GUM_REASON})"
            fi
            ;;
    esac
}

print_installer_banner() {
    if [[ -n "$GUM" ]]; then
        local title
        title="$("$GUM" style --foreground "#ff4d4d" --bold "Apache Flink Agents Installer")"
        "$GUM" style --border rounded --border-foreground "#ff4d4d" --padding "1 2" "$title"
        echo ""
        return
    fi

    echo -e "${ACCENT}${BOLD}"
    echo "  Apache Flink Agents Installer"
    echo ""
}

detect_os_or_die() {
    OS="unknown"
    if [[ "$OSTYPE" == "darwin"* ]]; then
        OS="macos"
    elif [[ "$OSTYPE" == "linux-gnu"* ]] || [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
        OS="linux"
    fi

    if [[ "$OS" == "unknown" ]]; then
        ui_error "Unsupported operating system"
        echo "This installer supports macOS and Linux (including WSL)."
        exit 1
    fi

    ui_success "Detected: $OS"
}

ui_info() {
    local msg="$*"
    if [[ -n "$GUM" ]]; then
        "$GUM" log --level info "$msg"
    else
        echo -e "${MUTED}·${NC} ${msg}"
    fi
}

ui_warn() {
    local msg="$*"
    if [[ -n "$GUM" ]]; then
        "$GUM" log --level warn "$msg"
    else
        echo -e "${WARN}!${NC} ${msg}"
    fi
}

ui_success() {
    local msg="$*"
    if [[ -n "$GUM" ]]; then
        local mark
        mark="$("$GUM" style --foreground "#00e5cc" --bold "✓")"
        echo "${mark} ${msg}"
    else
        echo -e "${SUCCESS}✓${NC} ${msg}"
    fi
}

ui_error() {
    local msg="$*"
    if [[ -n "$GUM" ]]; then
        "$GUM" log --level error "$msg"
    else
        echo -e "${ERROR}x${NC} ${msg}"
    fi
}

die() {
    ui_error "$*"
    exit 1
}

die_cancelled() {
    ui_info "Cancelled by user"
    exit 130
}

INSTALL_STAGE_TOTAL=5
INSTALL_STAGE_CURRENT=0

ui_section() {
    local title="$1"
    if [[ -n "$GUM" ]]; then
        "$GUM" style --bold --foreground "#ff4d4d" --padding "1 0" "$title"
    else
        echo ""
        echo -e "${ACCENT}${BOLD}${title}${NC}"
    fi
}

ui_stage() {
    local title="$1"
    INSTALL_STAGE_CURRENT=$((INSTALL_STAGE_CURRENT + 1))
    ui_section "[${INSTALL_STAGE_CURRENT}/${INSTALL_STAGE_TOTAL}] ${title}"
}

ui_kv() {
    local key="$1"
    local value="$2"
    if [[ -n "$GUM" ]]; then
        local key_part value_part
        key_part="$("$GUM" style --foreground "#5a6480" --width 20 "$key")"
        value_part="$("$GUM" style --bold "$value")"
        "$GUM" join --horizontal "$key_part" "$value_part"
    else
        echo -e "${MUTED}${key}:${NC} ${value}"
    fi
}

ui_celebrate() {
    local msg="$1"
    if [[ -n "$GUM" ]]; then
        "$GUM" style --bold --foreground "#00e5cc" "$msg"
    else
        echo -e "${SUCCESS}${BOLD}${msg}${NC}"
    fi
}

mark_explicit() {
    local var="$1"
    if [[ -n "${!var:-}" ]]; then
        printf -v "${var}_EXPLICIT" '%s' '1'
    else
        printf -v "${var}_EXPLICIT" '%s' '0'
    fi
}

mark_explicit FLINK_VERSION
mark_explicit INSTALL_DIR
mark_explicit VENV_DIR

FLINK_VERSION="${FLINK_VERSION:-2.2.0}"
FLINK_AGENTS_VERSION="${FLINK_AGENTS_VERSION:-0.2.1}"
FLINK_SCALA_VERSION="${FLINK_SCALA_VERSION:-2.12}"
FLINK_BASE_URL="${FLINK_BASE_URL:-https://dlcdn.apache.org/flink}"
# Flink Agents JARs live next to Flink on the ASF mirror network. Override
# this to point at archive.apache.org if you need a non-current release.
FLINK_AGENTS_BASE_URL="${FLINK_AGENTS_BASE_URL:-https://dlcdn.apache.org/flink}"
# Direct (non-mirrored) ASF download host. We hit it for the SHA512 sidecar
# because the mirror network does not redistribute checksum files.
FLINK_AGENTS_CHECKSUM_BASE_URL="${FLINK_AGENTS_CHECKSUM_BASE_URL:-https://downloads.apache.org/flink}"

FLINK_SUPPORTED_VERSIONS=("2.2.0" "2.1.1" "2.0.1" "1.20.3")
FLINK_RECOMMENDED_VERSION="2.2.0"

INSTALL_FLINK="${INSTALL_FLINK:-Ask}"
ENABLE_PYFLINK="${ENABLE_PYFLINK:-Ask}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/flink}"
VENV_DIR="${VENV_DIR:-.flink-agents-env}"
PYTHON_BIN="${PYTHON_BIN:-}"
NO_PROMPT="${NO_PROMPT:-0}"
VERBOSE="${FLINK_AGENTS_VERBOSE:-0}"
DRY_RUN="${FLINK_AGENTS_DRY_RUN:-0}"
VERIFY_INSTALL="${FLINK_AGENTS_VERIFY_INSTALL:-0}"
HELP=0
PYFLINK_ACTUALLY_ENABLED=0

print_usage() {
    cat <<EOF
Apache Flink Agents Installer

Usage:
  bash install.sh [options]
  curl -fsSL <url>/install.sh | bash -s -- [options]

Options:
  --non-interactive       Non-interactive mode (accept all defaults)
  --install-flink         Download and install Apache Flink
  --enable-pyflink        Enable PyFlink and install Python packages
  --verbose               Print debug output (set -x)
  --dry-run               Print install plan without making changes
  --verify                Run post-install verification checks
  --python <path>         Path to a Python3 interpreter (overrides PATH lookup)
  --help, -h              Show this help

Environment variables:
  FLINK_VERSION             Flink version
  FLINK_AGENTS_VERSION      Flink Agents version to install
  FLINK_SCALA_VERSION       Scala version suffix (default: 2.12)
  FLINK_BASE_URL            Mirror base URL for Flink (default: https://dlcdn.apache.org/flink)
  FLINK_AGENTS_BASE_URL     Mirror base URL for Flink Agents JARs (default: https://dlcdn.apache.org/flink)
  FLINK_AGENTS_CHECKSUM_BASE_URL  Direct ASF URL for SHA512 sidecars (default: https://downloads.apache.org/flink)
  INSTALL_FLINK             Ask|Yes|No (default: Ask)
  ENABLE_PYFLINK            Ask|Yes|No (default: Ask)
  INSTALL_DIR               Flink install directory (default: \$HOME/.local/flink)
  VENV_DIR                  Python venv directory (default: .flink-agents-env)
  PYTHON_BIN                Path to Python3 interpreter (default: auto-detect python3 on PATH)
  NO_PROMPT                 1 to disable all prompts
  FLINK_AGENTS_VERBOSE      1 to enable verbose output
  FLINK_AGENTS_DRY_RUN      1 to enable dry-run mode
  FLINK_AGENTS_VERIFY_INSTALL  1 to enable post-install verification

Examples:
  bash install.sh --install-flink --enable-pyflink --non-interactive
  FLINK_VERSION=2.2.0 bash install.sh --verbose
  bash install.sh --dry-run
EOF
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

# Normalize a filesystem path so downstream string interpolation can rely on
# a consistent shape. Empty input is passed through (callers handle "user
# entered nothing"). Otherwise:
#   - "~" prefix expands to $HOME
#   - relative paths are anchored at $PWD
#   - "/./" segments and trailing "/." are folded away
#   - trailing slashes are stripped, except for the root "/"
#   - runs of '/' collapse to a single '/'
normalize_path() {
    local p="$1"
    if [[ -z "$p" ]]; then
        printf ''
        return 0
    fi
    p="${p/#\~/$HOME}"
    if [[ "$p" != /* ]]; then
        p="$PWD/$p"
    fi
    while [[ "$p" == *//* ]]; do
        p="${p//\/\//\/}"
    done
    while [[ "$p" == */./* ]]; do
        p="${p//\/.\//\/}"
    done
    while [[ "$p" == */. ]]; do
        p="${p%/.}"
    done
    while [[ "${#p}" -gt 1 && "$p" == */ ]]; do
        p="${p%/}"
    done
    printf '%s' "$p"
}

is_valid_tgz() {
    local archive="$1"
    [[ -f "$archive" ]] || return 1
    tar -tzf "$archive" >/dev/null 2>&1
}

is_promptable() {
    if [[ "$NO_PROMPT" == "1" ]]; then
        return 1
    fi
    if [[ -r /dev/tty && -w /dev/tty ]]; then
        return 0
    fi
    return 1
}

choose_install_method_interactive() {
    local prompt="$1"

    if ! is_promptable; then
        return 1
    fi

    if [[ -n "$GUM" ]] && gum_is_tty; then
        local selection _rc=0
        selection="$("$GUM" choose \
            --header "$prompt" \
            --cursor-prefix "❯ " \
            "Yes" "No" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
        [[ "$selection" == "Yes" ]]
        return
    fi

    local answer=""
    printf '%s [y/n]: ' "$prompt" > /dev/tty
    read -r answer < /dev/tty || die_cancelled

    [[ "$answer" =~ ^[Yy]$ ]]
}

prompt_flink_version_interactive() {
    if ! is_promptable; then
        return 1
    fi

    local labels=()
    local v
    for v in "${FLINK_SUPPORTED_VERSIONS[@]}"; do
        if [[ "$v" == "$FLINK_RECOMMENDED_VERSION" ]]; then
            labels+=("$v (recommended)")
        else
            labels+=("$v")
        fi
    done

    local selection=""
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        selection="$("$GUM" choose \
            --header "Select Flink version" \
            --cursor-prefix "❯ " \
            "${labels[@]}" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
        selection="${selection%% *}"
    else
        printf 'Select Flink version:\n' > /dev/tty
        local i=1
        for v in "${FLINK_SUPPORTED_VERSIONS[@]}"; do
            local suffix=""
            [[ "$v" == "$FLINK_RECOMMENDED_VERSION" ]] && suffix=" (recommended)"
            printf '  %d) %s%s\n' "$i" "$v" "$suffix" > /dev/tty
            i=$((i+1))
        done
        local answer=""
        printf 'Enter choice [1-%d, default %s]: ' \
            "${#FLINK_SUPPORTED_VERSIONS[@]}" "$FLINK_RECOMMENDED_VERSION" > /dev/tty
        read -r answer < /dev/tty || die_cancelled
        if [[ "$answer" =~ ^[0-9]+$ ]] \
           && (( answer >= 1 && answer <= ${#FLINK_SUPPORTED_VERSIONS[@]} )); then
            selection="${FLINK_SUPPORTED_VERSIONS[$((answer-1))]}"
        fi
    fi

    if [[ -z "$selection" ]]; then
        selection="$FLINK_RECOMMENDED_VERSION"
    fi
    FLINK_VERSION="$selection"
    return 0
}

# Populate FLINK_VERSION from an existing FLINK_HOME. Tries to parse
# `lib/flink-dist-<ver>.jar` first because it's instant; falls back to
# `bin/flink --version`, which is authoritative but spins up a JVM and can
# take 3-10s on cold start. Returns 0 on success.
detect_flink_version_from_home() {
    [[ -n "${FLINK_HOME:-}" && -d "$FLINK_HOME" ]] || return 1

    local major_minor_patch=""

    # Fast path: filename inspection. Avoids JVM startup entirely.
    local jar
    for jar in "$FLINK_HOME/lib"/flink-dist-[0-9]*.jar; do
        [[ -f "$jar" ]] || continue
        major_minor_patch="$(basename "$jar" | sed -E -n 's/^flink-dist-([0-9]+\.[0-9]+\.[0-9]+)\.jar$/\1/p')"
        [[ -n "$major_minor_patch" ]] && break
    done

    # Slow path: ask the Flink CLI. JVM startup means a few seconds of
    # silence, so signal what's happening.
    if [[ -z "$major_minor_patch" && -x "$FLINK_HOME/bin/flink" ]]; then
        ui_info "Detecting Flink version (running '${FLINK_HOME}/bin/flink --version', this may take a few seconds)..."
        local out
        out="$("$FLINK_HOME/bin/flink" --version 2>/dev/null || true)"
        major_minor_patch="$(printf '%s\n' "$out" | sed -E -n 's/.*Version:[[:space:]]*([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' | head -n1)"
    fi

    if [[ -z "$major_minor_patch" ]]; then
        return 1
    fi

    FLINK_VERSION="$major_minor_patch"
    return 0
}

plan_flink() {
    case "$INSTALL_FLINK" in
        Yes|No)
            ;;
        Ask)
            if choose_install_method_interactive "Do you want this script to download and install Apache Flink?"; then
                INSTALL_FLINK=Yes
            else
                INSTALL_FLINK=No
            fi
            ;;
        *)
            die "Unsupported INSTALL_FLINK value: ${INSTALL_FLINK}. Use: Ask|Yes|No"
            ;;
    esac

    if [[ "$INSTALL_FLINK" == "No" ]]; then
        if [[ -n "${FLINK_HOME:-}" ]]; then
            FLINK_HOME="$(normalize_path "$FLINK_HOME")"
        fi
        if is_promptable; then
            while [[ -z "${FLINK_HOME:-}" || ! -d "${FLINK_HOME}" || ! -d "${FLINK_HOME}/lib" ]]; do
                if [[ -n "${FLINK_HOME:-}" ]]; then
                    if [[ ! -d "${FLINK_HOME}" ]]; then
                        ui_warn "Path does not exist: ${FLINK_HOME}"
                    else
                        ui_warn "Not a valid Flink home (missing 'lib' directory): ${FLINK_HOME}"
                    fi
                fi
                FLINK_HOME="$(prompt_path_input "Enter the path to your existing FLINK_HOME (version >= 1.20)" "/path/to/flink-${FLINK_VERSION}")"
            done
            export FLINK_HOME
        fi
        [[ -n "${FLINK_HOME:-}" ]] || die "FLINK_HOME is not set."
        [[ -d "$FLINK_HOME" ]] || die "FLINK_HOME does not exist: $FLINK_HOME"
        [[ -d "$FLINK_HOME/lib" ]] || die "Invalid FLINK_HOME (missing lib directory): $FLINK_HOME"
        ui_success "FLINK_HOME accepted: $FLINK_HOME"
        if detect_flink_version_from_home; then
            ui_success "Detected Flink version: $FLINK_VERSION"
        else
            ui_warn "Could not auto-detect Flink version from $FLINK_HOME; assuming ${FLINK_VERSION}."
            ui_warn "If JAR copy fails, set FLINK_VERSION explicitly (e.g. FLINK_VERSION=2.1.1 bash install.sh)."
        fi
        FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
        return
    fi

    if [[ "$FLINK_VERSION_EXPLICIT" -eq 0 ]]; then
        prompt_flink_version_interactive || true
    fi

    if [[ "$INSTALL_DIR_EXPLICIT" -eq 0 ]] && is_promptable; then
        INSTALL_DIR="$(prompt_path_choice_interactive \
            "Choose Flink install directory" \
            "$INSTALL_DIR" \
            "/path/to/flink-install-dir")"
    fi

    INSTALL_DIR="$(normalize_path "$INSTALL_DIR")"
    FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
    FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
}

plan_pyflink() {
    case "$ENABLE_PYFLINK" in
        Yes|No)
            ;;
        Ask)
            if choose_install_method_interactive "Create a Python venv with PyFlink and flink-agents? (Only needed for Python API users; Java users can select No)"; then
                ENABLE_PYFLINK=Yes
            else
                ENABLE_PYFLINK=No
            fi
            ;;
        *)
            die "Unsupported ENABLE_PYFLINK value: ${ENABLE_PYFLINK}. Use: Ask|Yes|No"
            ;;
    esac

    if [[ "$ENABLE_PYFLINK" == "No" ]]; then
        return
    fi

    PYFLINK_ACTUALLY_ENABLED=1
    resolve_python

    if [[ "$VENV_DIR_EXPLICIT" -eq 0 ]] && is_promptable; then
        VENV_DIR="$(prompt_path_choice_interactive \
            "Choose Python venv directory" \
            "$VENV_DIR" \
            "/path/to/venv")"
    fi

    VENV_DIR="$(normalize_path "$VENV_DIR")"
}

# Echo one of: "confirm" | "edit" | "cancel"
confirm_plan_action_interactive() {
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local selection _rc=0
        selection="$("$GUM" choose \
            --header "Proceed with installation?" \
            --cursor-prefix "❯ " \
            "Proceed" "Edit a setting" "Cancel" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
        case "$selection" in
            Proceed)          echo confirm ;;
            "Edit a setting") echo edit ;;
            *)                echo cancel ;;
        esac
        return
    fi

    printf 'Proceed with installation?\n' > /dev/tty
    printf '  1) Proceed\n' > /dev/tty
    printf '  2) Edit a setting\n' > /dev/tty
    printf '  3) Cancel\n' > /dev/tty
    local answer=""
    printf 'Enter choice [1-3, default 1]: ' > /dev/tty
    read -r answer < /dev/tty || die_cancelled
    case "$answer" in
        2) echo edit ;;
        3) echo cancel ;;
        *) echo confirm ;;
    esac
}

# Prompt the user to pick a Flink home interactively. Loops until a valid
# path (exists and has a lib/ subdir) is provided. Mirrors the loop in
# plan_flink so the two stay in sync.
edit_prompt_flink_home() {
    FLINK_HOME=""
    while [[ -z "${FLINK_HOME:-}" || ! -d "${FLINK_HOME}" || ! -d "${FLINK_HOME}/lib" ]]; do
        if [[ -n "${FLINK_HOME:-}" ]]; then
            if [[ ! -d "${FLINK_HOME}" ]]; then
                ui_warn "Path does not exist: ${FLINK_HOME}"
            else
                ui_warn "Not a valid Flink home (missing 'lib' directory): ${FLINK_HOME}"
            fi
        fi
        FLINK_HOME="$(prompt_path_input "Enter the path to your existing FLINK_HOME (version >= 1.20)" "/path/to/flink-${FLINK_VERSION}")"
    done
    export FLINK_HOME
}

# Show a menu of currently-applicable plan fields and re-prompt for the
# selected one. After the edit, recomputes FLINK_HOME / FLINK_MAJOR_MINOR
# so the next show_install_plan reflects the change.
edit_plan_interactive() {
    local labels=()
    local actions=()

    labels+=("Install Flink: $INSTALL_FLINK")
    actions+=("install_flink")

    if [[ "$INSTALL_FLINK" == "Yes" ]]; then
        labels+=("Flink version: $FLINK_VERSION")
        actions+=("flink_version")
        labels+=("Install directory: $INSTALL_DIR")
        actions+=("install_dir")
    else
        labels+=("FLINK_HOME: ${FLINK_HOME:-<unset>}")
        actions+=("flink_home")
    fi

    labels+=("Enable PyFlink: $ENABLE_PYFLINK")
    actions+=("enable_pyflink")

    if [[ "$ENABLE_PYFLINK" == "Yes" ]]; then
        labels+=("Venv directory: $VENV_DIR")
        actions+=("venv_dir")
    fi

    labels+=("Back")
    actions+=("back")

    local picked_index=-1
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        local selected=""
        selected="$("$GUM" choose \
            --header "Edit a setting" \
            --cursor-prefix "❯ " \
            "${labels[@]}" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
        local i=0
        for l in "${labels[@]}"; do
            if [[ "$l" == "$selected" ]]; then
                picked_index=$i
                break
            fi
            i=$((i+1))
        done
    else
        printf 'Edit a setting:\n' > /dev/tty
        local i=1
        for l in "${labels[@]}"; do
            printf '  %d) %s\n' "$i" "$l" > /dev/tty
            i=$((i+1))
        done
        local answer=""
        printf 'Enter choice [1-%d]: ' "${#labels[@]}" > /dev/tty
        read -r answer < /dev/tty || die_cancelled
        if [[ "$answer" =~ ^[0-9]+$ ]] && (( answer >= 1 && answer <= ${#labels[@]} )); then
            picked_index=$((answer - 1))
        fi
    fi

    (( picked_index < 0 )) && return 0

    local action="${actions[$picked_index]}"
    case "$action" in
        install_flink)
            if choose_install_method_interactive "Install Flink?"; then
                INSTALL_FLINK=Yes
                FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
            else
                INSTALL_FLINK=No
                edit_prompt_flink_home
            fi
            FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
            ;;
        flink_version)
            prompt_flink_version_interactive || true
            FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
            FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
            ;;
        install_dir)
            INSTALL_DIR="$(prompt_path_choice_interactive \
                "Choose Flink install directory" \
                "$INSTALL_DIR" \
                "/path/to/flink-install-dir")"
            INSTALL_DIR="$(normalize_path "$INSTALL_DIR")"
            FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
            ;;
        flink_home)
            edit_prompt_flink_home
            ;;
        enable_pyflink)
            if choose_install_method_interactive "Enable PyFlink?"; then
                if [[ "$ENABLE_PYFLINK" != "Yes" ]]; then
                    ENABLE_PYFLINK=Yes
                    PYFLINK_ACTUALLY_ENABLED=1
                    resolve_python
                fi
            else
                ENABLE_PYFLINK=No
            fi
            ;;
        venv_dir)
            VENV_DIR="$(prompt_path_choice_interactive \
                "Choose Python venv directory" \
                "$VENV_DIR" \
                "/path/to/venv")"
            VENV_DIR="$(normalize_path "$VENV_DIR")"
            ;;
        back|*)
            ;;
    esac
}

confirm_install_plan() {
    if [[ "$NO_PROMPT" == "1" ]] || ! is_promptable; then
        return 0
    fi
    while true; do
        case "$(confirm_plan_action_interactive)" in
            confirm) return 0 ;;
            edit)
                edit_plan_interactive
                # Re-display the updated plan so the user sees their change
                # in context before re-prompting.
                show_install_plan
                ;;
            cancel)
                ui_info "Installation cancelled by user."
                exit 0
                ;;
        esac
    done
}

install_flink_if_needed() {
    if [[ "$INSTALL_FLINK" == "No" ]]; then
        ui_info "Skipping Flink download/install (using existing FLINK_HOME)."
        ui_success "FLINK_HOME resolved: $FLINK_HOME"
        return
    fi

    local ARCHIVE_NAME="flink-${FLINK_VERSION}-bin-scala_${FLINK_SCALA_VERSION}.tgz"
    local ARCHIVE_URL="${FLINK_BASE_URL}/flink-${FLINK_VERSION}/${ARCHIVE_NAME}"

    detect_downloader
    require_cmd tar

    if ! mkdir -p "$INSTALL_DIR"; then
        die "Failed to create INSTALL_DIR=$INSTALL_DIR. Please run with proper permissions or set INSTALL_DIR to a writable path."
    fi
    if [[ -f "${INSTALL_DIR}/${ARCHIVE_NAME}" ]] && ! is_valid_tgz "${INSTALL_DIR}/${ARCHIVE_NAME}"; then
        ui_warn "Existing archive is corrupted; re-downloading: ${INSTALL_DIR}/${ARCHIVE_NAME}"
        rm -f "${INSTALL_DIR}/${ARCHIVE_NAME}"
    fi

    if [[ ! -f "${INSTALL_DIR}/${ARCHIVE_NAME}" ]]; then
        ui_info "Downloading ${ARCHIVE_URL}"
        download_file "$ARCHIVE_URL" "${INSTALL_DIR}/${ARCHIVE_NAME}"
    else
        ui_info "Reusing existing archive: ${INSTALL_DIR}/${ARCHIVE_NAME}"
    fi

    if ! is_valid_tgz "${INSTALL_DIR}/${ARCHIVE_NAME}"; then
        die "Downloaded archive is invalid or truncated: ${INSTALL_DIR}/${ARCHIVE_NAME}"
    fi

    if [[ -d "${INSTALL_DIR}/flink-${FLINK_VERSION}" ]] && [[ ! -d "${INSTALL_DIR}/flink-${FLINK_VERSION}/lib" ]]; then
        ui_warn "Existing Flink home is incomplete; re-extracting: ${INSTALL_DIR}/flink-${FLINK_VERSION}"
        rm -rf "${INSTALL_DIR}/flink-${FLINK_VERSION}"
    fi

    if [[ ! -d "${INSTALL_DIR}/flink-${FLINK_VERSION}" ]]; then
        ui_info "Extracting Flink to ${INSTALL_DIR}"
        tar -xzf "${INSTALL_DIR}/${ARCHIVE_NAME}" -C "$INSTALL_DIR"
    else
        ui_info "Reusing existing Flink home: ${INSTALL_DIR}/flink-${FLINK_VERSION}"
    fi

    export FLINK_HOME
    ui_success "FLINK_HOME resolved: $FLINK_HOME"
}

prompt_path_input() {
    local header="$1"
    local placeholder="$2"
    local input=""
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        input="$("$GUM" input \
            --header "$header" \
            --placeholder "$placeholder" \
            --width 70 < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
    else
        printf '%s: ' "$header" > /dev/tty
        read -r input < /dev/tty || die_cancelled
    fi
    printf '%s' "$(normalize_path "$input")"
}

prompt_path_choice_interactive() {
    local header="$1"
    local default_path="$2"
    local placeholder="$3"

    if ! is_promptable; then
        printf '%s' "$default_path"
        return 0
    fi

    local default_label="Default: ${default_path}"
    local custom_label="Custom..."
    local selection=""

    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        selection="$("$GUM" choose \
            --header "$header" \
            --cursor-prefix "❯ " \
            "$default_label" "$custom_label" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
    else
        printf '%s\n' "$header" > /dev/tty
        printf '  1) %s\n' "$default_label" > /dev/tty
        printf '  2) %s\n' "$custom_label" > /dev/tty
        local answer=""
        printf 'Enter choice [1-2, default 1]: ' > /dev/tty
        read -r answer < /dev/tty || die_cancelled
        case "$answer" in
            2) selection="$custom_label" ;;
            *) selection="$default_label" ;;
        esac
    fi

    if [[ "$selection" != "$custom_label" ]]; then
        printf '%s' "$(normalize_path "$default_path")"
        return 0
    fi

    local input=""
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        input="$("$GUM" input \
            --header "$header" \
            --placeholder "$placeholder" \
            --width 70 < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
    else
        printf 'Enter custom path: ' > /dev/tty
        read -r input < /dev/tty || die_cancelled
    fi

    if [[ -z "$input" ]]; then
        ui_warn "Empty path; falling back to default: $default_path"
        printf '%s' "$(normalize_path "$default_path")"
        return 0
    fi

    printf '%s' "$(normalize_path "$input")"
}

copy_pyflink_jar() {
    local pyflink_jar="$FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar"
    [[ -f "$pyflink_jar" ]] || die "Missing required PyFlink jar: $pyflink_jar"

    ui_info "Copying PyFlink jar into Flink lib"
    cp "$pyflink_jar" "$FLINK_HOME/lib/"
}

create_venv() {
    local venv_err
    venv_err="$(mktempfile)"
    if "$PYTHON_BIN" -m venv "$VENV_DIR" 2>"$venv_err"; then
        return 0
    fi

    ui_error "Failed to create virtual environment at $VENV_DIR"
    if [[ -s "$venv_err" ]]; then
        sed 's/^/  /' "$venv_err" >&2 || true
    fi
    die "Virtual environment creation failed"
}

# Run `python -m pip install <args>` with reduced terminal noise.
# Strategy depends on environment:
#   VERBOSE=1                : full output, pass-through
#   gum available + tty      : `gum spin --show-error` (spinner; pip output
#                              is hidden unless pip exits non-zero)
#   plain tty (no gum)       : single rolling status line via \r\033[K;
#                              full output goes to a temp log and is shown
#                              only on failure
#   non-tty (CI / piped)     : pip --quiet (errors still surface)
pip_install_quiet() {
    local pkgs=("$@")

    if [[ "$VERBOSE" == "1" ]]; then
        python -m pip install "${pkgs[@]}"
        return
    fi

    if [[ -n "$GUM" ]] && gum_is_tty; then
        "$GUM" spin --show-error \
            --title "pip install ${pkgs[*]}" \
            -- python -m pip install "${pkgs[@]}"
        return
    fi

    if [[ ! -t 1 ]]; then
        python -m pip install -q "${pkgs[@]}"
        return
    fi

    local log
    log="$(mktempfile)"
    local cols max
    cols="$(tput cols 2>/dev/null || echo 80)"
    local prefix="  · "
    max=$((cols - ${#prefix} - 4))
    (( max < 20 )) && max=20

    local rc=0
    {
        python -m pip install "${pkgs[@]}" 2>&1 \
            | while IFS= read -r line; do
                printf '%s\n' "$line" >> "$log"
                printf '\r\033[K%s%s' "$prefix" "${line:0:$max}"
              done
    } || rc=$?

    # Clear the rolling status line.
    printf '\r\033[K'

    if (( rc != 0 )); then
        ui_error "pip install failed; full output:"
        sed 's/^/  /' "$log" >&2
        return $rc
    fi
}

setup_python_env() {
    if [[ ! -d "$VENV_DIR" ]]; then
        ui_info "Creating virtual environment: $VENV_DIR"
        create_venv
    else
        ui_info "Reusing existing virtual environment: $VENV_DIR"
    fi

    source "$VENV_DIR/bin/activate"

    export PIP_PROGRESS_BAR=off
    export PIP_NO_COLOR=1
    export PIP_NO_INPUT=1

    ui_info "Installing Python packages (may take a few minutes)..."
    pip_install_quiet \
        "flink-agents==${FLINK_AGENTS_VERSION}" \
        "apache-flink==${FLINK_VERSION}"
}

# Compute the relative path of the flink-agents JAR under the ASF mirror,
# e.g. flink-agents-0.2.1/flink-agents-dist-flink-2.2-0.2.1.jar
flink_agents_jar_relpath() {
    printf '%s' "flink-agents-${FLINK_AGENTS_VERSION}/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}-${FLINK_AGENTS_VERSION}.jar"
}

# Compare a downloaded artifact against its .sha512 sidecar. The sidecar
# format from ASF is "<hex>  <filename>" (single line). Best-effort: a
# missing sidecar or missing sha512 tool warns but does not fail (mirrors
# may not always carry the checksum on time).
verify_flink_agents_jar_sha512() {
    local jar="$1"
    local sha_url="$2"
    local sha_file
    sha_file="$(mktempfile)"

    if ! download_file "$sha_url" "$sha_file" >/dev/null 2>&1; then
        ui_warn "SHA512 sidecar unavailable (${sha_url}); skipping verification"
        return 0
    fi

    local expected actual
    expected="$(awk 'NR==1 {print $1}' "$sha_file" 2>/dev/null || true)"
    if [[ -z "$expected" ]]; then
        ui_warn "SHA512 sidecar is empty; skipping verification"
        return 0
    fi
    # SHA-512 hex is exactly 128 lowercase/upper hex chars. If the sidecar
    # didn't look like a real ASF .sha512 file, warn instead of failing.
    if [[ ! "$expected" =~ ^[A-Fa-f0-9]{128}$ ]]; then
        ui_warn "SHA512 sidecar at ${sha_url} is not a valid hash; skipping verification"
        return 0
    fi

    if command -v sha512sum >/dev/null 2>&1; then
        actual="$(sha512sum "$jar" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
        actual="$(shasum -a 512 "$jar" | awk '{print $1}')"
    else
        ui_warn "Neither sha512sum nor shasum available; skipping SHA512 verification"
        return 0
    fi

    if [[ "${expected,,}" != "${actual,,}" ]]; then
        die "SHA512 mismatch for $(basename "$jar") (expected ${expected:0:16}…, got ${actual:0:16}…)"
    fi
    ui_success "SHA512 verified"
}

# Download flink-agents-dist-flink-<flink_major.minor>-<agents_ver>.jar
# directly from the ASF mirror into FLINK_HOME/lib. This replaces the older
# wheel-extraction path, so Java-only users no longer need Python at all.
install_flink_agents_jar() {
    [[ -n "${FLINK_HOME:-}" ]] || die "FLINK_HOME is not set"
    [[ -d "$FLINK_HOME/lib" ]] || die "Invalid FLINK_HOME (missing lib): $FLINK_HOME"

    local relpath jar_name target url sha_url
    relpath="$(flink_agents_jar_relpath)"
    jar_name="$(basename "$relpath")"
    target="$FLINK_HOME/lib/$jar_name"
    url="${FLINK_AGENTS_BASE_URL}/${relpath}"
    sha_url="${FLINK_AGENTS_CHECKSUM_BASE_URL}/${relpath}.sha512"

    detect_downloader

    if [[ -f "$target" ]]; then
        ui_info "Reusing existing JAR: ${target}"
        verify_flink_agents_jar_sha512 "$target" "$sha_url" || true
        return 0
    fi

    ui_info "Downloading ${url}"
    if ! download_file "$url" "$target"; then
        rm -f "$target" 2>/dev/null || true
        die "Failed to download flink-agents JAR from ${url}"
    fi

    if [[ ! -s "$target" ]]; then
        rm -f "$target" 2>/dev/null || true
        die "Downloaded an empty file: ${target}"
    fi

    verify_flink_agents_jar_sha512 "$target" "$sha_url"
    ui_success "Installed: ${jar_name} → ${FLINK_HOME}/lib"
}

check_java() {
    if ! command -v java &>/dev/null; then
        ui_warn "Java not found on PATH"
        if [[ "$OS" == "macos" ]]; then
            ui_info "Install Java: brew install openjdk@17"
        elif [[ "$OS" == "linux" ]]; then
            ui_info "Install Java: sudo apt install openjdk-17-jdk (Debian/Ubuntu)"
            ui_info "              sudo yum install java-17-openjdk-devel (RHEL/CentOS)"
        fi
        ui_info "Flink requires Java 11+ to run"
        return 1
    fi

    local java_version_output
    java_version_output="$(java -version 2>&1 | head -n1)"
    local java_major=""
    java_major="$(echo "$java_version_output" | sed -E -n 's/.*version "?([0-9]+).*/\1/p')"

    if [[ -z "$java_major" ]]; then
        ui_warn "Could not parse Java version from: $java_version_output"
        return 1
    fi

    if [[ "$java_major" -lt 11 ]]; then
        ui_error "Java $java_major detected, but Flink requires Java 11+"
        ui_info "Please upgrade your Java installation"
        return 1
    fi

    ui_success "Java $java_major found"

    if [[ -z "${JAVA_HOME:-}" ]]; then
        ui_info "JAVA_HOME is not set (Flink will try to detect it automatically)"
    fi
    return 0
}

validate_python_bin() {
    local bin="$1"
    [[ -n "$bin" ]] || return 1
    command -v "$bin" >/dev/null 2>&1 || return 1

    local py_version_output
    py_version_output="$("$bin" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null || true)"
    [[ -n "$py_version_output" ]] || return 1

    local py_major py_minor
    py_major="${py_version_output%%.*}"
    py_minor="${py_version_output##*.}"

    if [[ "$py_major" -ne 3 ]] || [[ "$py_minor" -lt 10 ]] || [[ "$py_minor" -ge 12 ]]; then
        return 1
    fi
    return 0
}

resolve_python() {
    if [[ -n "$PYTHON_BIN" ]]; then
        if validate_python_bin "$PYTHON_BIN"; then
            ui_success "Using Python: $PYTHON_BIN"
            return 0
        fi
        die "PYTHON_BIN is invalid or unsupported (need >=3.10 and <3.12): $PYTHON_BIN"
    fi

    if validate_python_bin python3; then
        PYTHON_BIN="python3"
        local v
        v="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null)"
        ui_success "Python $v found on PATH"
        return 0
    fi

    if command -v python3 >/dev/null 2>&1; then
        ui_warn "python3 on PATH is incompatible (Flink Agents requires Python >=3.10 and <3.12)"
    else
        ui_warn "python3 not found on PATH"
    fi

    if ! is_promptable; then
        die "No compatible Python found. Set PYTHON_BIN or pass --python <path>."
    fi

    local input
    input="$(prompt_path_input "Enter path to a Python interpreter" "/path/to/python3")"
    if [[ -z "$input" ]]; then
        die "No Python interpreter provided."
    fi
    if ! validate_python_bin "$input"; then
        die "Provided Python is invalid or unsupported (need >=3.10 and <3.12): $input"
    fi
    PYTHON_BIN="$input"
    ui_success "Using Python: $PYTHON_BIN"
    return 0
}

show_install_plan() {
    ui_section "Installation plan"
    ui_kv "OS" "$OS"
    ui_kv "Flink version" "$FLINK_VERSION"
    ui_kv "Flink Agents version" "$FLINK_AGENTS_VERSION"
    ui_kv "Install Flink" "$INSTALL_FLINK"
    ui_kv "Install directory" "$INSTALL_DIR"
    ui_kv "Enable PyFlink" "$ENABLE_PYFLINK"
    if [[ "$ENABLE_PYFLINK" == "Yes" ]] || [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        ui_kv "Venv directory" "$VENV_DIR"
        if [[ -n "$PYTHON_BIN" ]]; then
            ui_kv "Python interpreter" "$PYTHON_BIN"
        fi
    fi
    if [[ -n "${FLINK_HOME:-}" ]]; then
        ui_kv "FLINK_HOME" "$FLINK_HOME"
    fi
    if [[ -n "${JAVA_HOME:-}" ]]; then
        ui_kv "JAVA_HOME" "$JAVA_HOME"
    fi
    if [[ "$DRY_RUN" == "1" ]]; then
        ui_kv "Dry run" "yes"
    fi
    if [[ "$VERIFY_INSTALL" == "1" ]]; then
        ui_kv "Verify" "yes"
    fi
}

verify_installation() {
    if [[ "${VERIFY_INSTALL}" != "1" ]]; then
        return 0
    fi

    ui_stage "Verifying installation"

    if [[ -x "$FLINK_HOME/bin/flink" ]]; then
        local flink_ver
        flink_ver="$("$FLINK_HOME/bin/flink" --version 2>/dev/null || true)"
        if [[ -n "$flink_ver" ]]; then
            ui_success "Flink binary: $flink_ver"
        else
            ui_warn "Flink binary exists but --version failed"
        fi
    else
        ui_warn "Flink binary not found at $FLINK_HOME/bin/flink"
    fi

    local expected_jar="$FLINK_HOME/lib/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}-${FLINK_AGENTS_VERSION}.jar"
    if [[ -f "$expected_jar" ]]; then
        ui_success "flink-agents JAR found: $(basename "$expected_jar")"
    else
        ui_error "Expected flink-agents JAR missing: $expected_jar"
        return 1
    fi

    if [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        if python -c "import flink_agents; print('flink-agents', flink_agents.__version__)" 2>/dev/null; then
            ui_success "flink-agents Python package verified"
        else
            ui_error "flink-agents Python package import failed"
            return 1
        fi

        if [[ -f "$FLINK_HOME/lib/flink-python-${FLINK_VERSION}.jar" ]]; then
            ui_success "flink-python JAR found in FLINK_HOME/lib"
        else
            ui_warn "flink-python-${FLINK_VERSION}.jar not found in $FLINK_HOME/lib"
        fi
    fi

    ui_success "Verification complete"
}

show_footer_links() {
    local docs_url="https://nightlies.apache.org/flink/flink-agents-docs-latest/"
    local issues_url="https://github.com/apache/flink-agents/issues"
    echo ""
    ui_info "Documentation: ${docs_url}"
    ui_info "Report issues: ${issues_url}"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --non-interactive)
                NO_PROMPT=1
                shift
                ;;
            --install-flink)
                INSTALL_FLINK=Yes
                shift
                ;;
            --enable-pyflink|--enable-pyFlink)
                ENABLE_PYFLINK=Yes
                shift
                ;;
            --verbose)
                VERBOSE=1
                shift
                ;;
            --dry-run)
                DRY_RUN=1
                shift
                ;;
            --verify)
                VERIFY_INSTALL=1
                shift
                ;;
            --python)
                if [[ $# -lt 2 ]]; then
                    die "--python requires a path argument"
                fi
                PYTHON_BIN="$2"
                shift 2
                ;;
            --python=*)
                PYTHON_BIN="${1#*=}"
                shift
                ;;
            --help|-h)
                HELP=1
                shift
                ;;
            *)
                ui_warn "Unknown option: $1"
                shift
                ;;
        esac
    done
}

configure_verbose() {
    if [[ "$VERBOSE" != "1" ]]; then
        return 0
    fi
    if [[ "$NPM_LOGLEVEL" == "error" ]]; then
        NPM_LOGLEVEL="notice"
    fi
    NPM_SILENT_FLAG=""
    set -x
}

main() {
    if [[ "$HELP" == "1" ]]; then
        print_usage
        return 0
    fi

    bootstrap_gum_temp || true
    print_installer_banner
    print_gum_status
    detect_os_or_die
    check_java || die "Java environment check failed. Please install Java 11 or newer."

    ui_stage "Planning Flink installation"
    plan_flink

    ui_stage "Planning Python environment"
    plan_pyflink

    show_install_plan

    if [[ "$DRY_RUN" == "1" ]]; then
        ui_success "Dry run complete (no changes made)"
        return 0
    fi

    confirm_install_plan

    ui_stage "Installing Apache Flink"
    install_flink_if_needed

    ui_stage "Installing Flink Agents"
    install_flink_agents_jar
    if [[ "$ENABLE_PYFLINK" == "Yes" ]]; then
        copy_pyflink_jar
        setup_python_env
    else
        ui_info "Skipping Python venv (ENABLE_PYFLINK=No)."
    fi

    ui_stage "Finalizing"

    verify_installation

    echo ""
    ui_celebrate "Apache Flink Agents installation finished!"
    ui_success "FLINK_HOME=$FLINK_HOME"
    if [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        ui_info "To use Python environment in a new shell :"
        ui_info "  source ${VENV_DIR}/bin/activate"
    fi
    ui_info "  export FLINK_HOME=${FLINK_HOME}"

    show_footer_links
}

if [[ "${FLINK_AGENTS_INSTALL_SH_NO_RUN:-0}" != "1" ]]; then
    parse_args "$@"
    configure_verbose
    main
fi

