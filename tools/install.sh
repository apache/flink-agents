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
        curl -fsSL --proto '=https' --tlsv1.2 --connect-timeout 5 --max-time 15 --retry 1 --retry-delay 1 --retry-connrefused -o "$output" "$url"
        return
    fi
    wget -q --https-only --secure-protocol=TLSv1_2 --tries=2 --timeout=15 -O "$output" "$url"
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

    echo -e "${INFO}· Installing gum v${GUM_VERSION}, please wait...${NC}"

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
        local title card
        title="$("$GUM" style --foreground "#ff4d4d" --bold "Apache Flink Agents Installer")"
        card="$(printf '%s\n' "$title")"
        "$GUM" style --border rounded --border-foreground "#ff4d4d" --padding "1 2" "$card"
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

INSTALL_STAGE_TOTAL=3
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

is_shell_function() {
    local name="${1:-}"
    [[ -n "$name" ]] && declare -F "$name" >/dev/null 2>&1
}

is_gum_raw_mode_failure() {
    local err_log="$1"
    [[ -s "$err_log" ]] || return 1
    grep -Eiq '(setrawmode|inappropriate ioctl for device)' "$err_log"
}

configure_verbose() {
    if [[ "$VERBOSE" != "1" ]]; then
        return 0
    fi
    set -x
}

FLINK_VERSION="${FLINK_VERSION:-2.2.0}"
FLINK_AGENTS_VERSION="${FLINK_AGENTS_VERSION:-0.2.1}"
FLINK_SCALA_VERSION="${FLINK_SCALA_VERSION:-2.12}"
FLINK_MAJOR_MINOR="${FLINK_VERSION%.*}"
FLINK_BASE_URL="${FLINK_BASE_URL:-https://dlcdn.apache.org/flink}"

INSTALL_FLINK="${INSTALL_FLINK:-ask}"
ENABLE_PYFLINK="${ENABLE_PYFLINK:-ask}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/flink}"
VENV_DIR="${VENV_DIR:-.flink-agents-env}"
NO_PROMPT="${NO_PROMPT:-0}"
VERBOSE="${FLINK_AGENTS_VERBOSE:-0}"
DRY_RUN="${FLINK_AGENTS_DRY_RUN:-0}"
VERIFY_INSTALL="${FLINK_AGENTS_VERIFY_INSTALL:-0}"
HELP=0
PYFLINK_ACTUALLY_ENABLED=0

ARCHIVE_NAME="flink-${FLINK_VERSION}-bin-scala_${FLINK_SCALA_VERSION}.tgz"
ARCHIVE_URL="${FLINK_BASE_URL}/flink-${FLINK_VERSION}/${ARCHIVE_NAME}"

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
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
        local selection
        selection="$("$GUM" choose \
            --header "$prompt" \
            --cursor-prefix "❯ " \
            "Yes" "No" < /dev/tty || true)"
        [[ "$selection" == "Yes" ]]
        return
    fi

    local answer=""
    printf '%s [y/n]: ' "$prompt" > /dev/tty
    read -r answer < /dev/tty || true

    [[ "$answer" =~ ^[Yy]$ ]]
}

install_flink_if_needed() {
    case "$INSTALL_FLINK" in
        yes)
            ;;
        no)
            ui_info "Skipping Flink download/install (INSTALL_FLINK=${INSTALL_FLINK})."
            return
            ;;
        ask)
            if ! choose_install_method_interactive "Do you want this script to download and install Flink ${FLINK_VERSION}?"; then
                ui_info "Skipping Flink download/install by user choice."
                return
            fi
            ;;
        *)
            die "Unsupported INSTALL_FLINK value: ${INSTALL_FLINK}. Use: ask|yes|no"
            ;;
    esac

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

    export FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
}

prompt_flink_home_interactive() {
    local input=""
    if [[ -n "$GUM" ]] && gum_is_tty; then
        input="$("$GUM" input \
            --header "Enter the path to your existing FLINK_HOME" \
            --placeholder "/path/to/flink-${FLINK_VERSION}" \
            --width 70 < /dev/tty || true)"
    else
        printf 'Enter FLINK_HOME path: ' > /dev/tty
        read -r input < /dev/tty || true
    fi
    input="${input/#\~/$HOME}"
    printf '%s' "$input"
}

resolve_flink_home() {
    if [[ -z "${FLINK_HOME:-}" || ! -d "${FLINK_HOME}" || ! -d "${FLINK_HOME}/lib" ]]; then
        if is_promptable; then
            FLINK_HOME="$(prompt_flink_home_interactive)"
            export FLINK_HOME
        fi
    fi

    [[ -n "${FLINK_HOME:-}" ]] || die "FLINK_HOME is not set."
    [[ -d "$FLINK_HOME" ]] || die "FLINK_HOME does not exist: $FLINK_HOME"
    [[ -d "$FLINK_HOME/lib" ]] || die "Invalid FLINK_HOME (missing lib directory): $FLINK_HOME"
    ui_success "FLINK_HOME resolved: $FLINK_HOME"
}

copy_pyflink_jar() {
    local pyflink_jar="$FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar"
    [[ -f "$pyflink_jar" ]] || die "Missing required PyFlink jar: $pyflink_jar"

    ui_info "Copying PyFlink jar into Flink lib"
    cp "$pyflink_jar" "$FLINK_HOME/lib/"
}

setup_python_env() {
    check_python || die "Python environment check failed. Please install Python >=3.10."

    if [[ ! -d "$VENV_DIR" ]]; then
        ui_info "Creating virtual environment: $VENV_DIR"
        python3 -m venv "$VENV_DIR"
    else
        ui_info "Reusing existing virtual environment: $VENV_DIR"
    fi

    source "$VENV_DIR/bin/activate"

    export PIP_PROGRESS_BAR=off
    export PIP_NO_COLOR=1
    export PIP_NO_INPUT=1

    ui_info "Installing Python packages"
    python -m pip install --upgrade pip
    python -m pip install "flink-agents==${FLINK_AGENTS_VERSION}" "apache-flink==${FLINK_VERSION}"
}

copy_flink_agents_jars() {
    local pkg_root
    pkg_root="$(python - <<'PY'
import pathlib
import flink_agents
print(pathlib.Path(flink_agents.__file__).resolve().parent)
PY
)"

    local version_lib_dir="${pkg_root}/lib/flink-${FLINK_MAJOR_MINOR}"
    local common_lib_dir="${pkg_root}/lib/common"

    [[ -d "$version_lib_dir" ]] || die "Flink Agents lib directory not found: $version_lib_dir"
    [[ -d "$common_lib_dir" ]] || die "Flink Agents common lib directory not found: $common_lib_dir"

    local jar

    ui_info "Copying Flink Agents common jar into Flink lib"
    local common_copied=0
    for jar in "$common_lib_dir"/flink-agents-dist-common-*.jar; do
        [[ -f "$jar" ]] || continue
        cp "$jar" "$FLINK_HOME/lib/"
        common_copied=1
    done
    [[ "$common_copied" -eq 1 ]] || die "No flink-agents-dist-common jar found in: $common_lib_dir"

    ui_info "Copying Flink Agents thin jar into Flink lib"
    local copied=0
    for jar in "$version_lib_dir"/flink-agents-dist-*.jar; do
        [[ -f "$jar" ]] || continue
        cp "$jar" "$FLINK_HOME/lib/"
        copied=1
    done
    [[ "$copied" -eq 1 ]] || die "No flink-agents-dist jar found in: $version_lib_dir"
}

should_enable_pyflink() {
    case "$ENABLE_PYFLINK" in
        yes)
            return 0
            ;;
        no)
            return 1
            ;;
        ask)
            if choose_install_method_interactive "Create a Python venv with PyFlink and flink-agents? (Only needed for Python API users; Java users can select No)"; then
                return 0
            fi
            return 1
            ;;
        *)
            die "Unsupported ENABLE_PYFLINK value: ${ENABLE_PYFLINK}. Use: ask|yes|no"
            ;;
    esac
}

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
  --help, -h              Show this help

Environment variables:
  FLINK_VERSION             Flink version to install (default: 2.2.0)
  FLINK_AGENTS_VERSION      Flink Agents version to install (default: 0.2.1)
  FLINK_SCALA_VERSION       Scala version suffix (default: 2.12)
  FLINK_BASE_URL            Mirror base URL (default: https://dlcdn.apache.org/flink)
  INSTALL_FLINK             ask|yes|no (default: ask)
  ENABLE_PYFLINK            ask|yes|no (default: ask)
  INSTALL_DIR               Flink install directory (default: \$HOME/.local/flink)
  VENV_DIR                  Python venv directory (default: .flink-agents-env)
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
    java_major="$(echo "$java_version_output" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"

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

check_python() {
    if ! command -v python3 &>/dev/null; then
        ui_error "python3 not found on PATH"
        return 1
    fi

    local py_version_output
    py_version_output="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null || true)"

    if [[ -z "$py_version_output" ]]; then
        ui_error "Could not parse python3 version"
        return 1
    fi

    local py_major py_minor
    py_major="${py_version_output%%.*}"
    py_minor="${py_version_output##*.}"

    if [[ "$py_major" -ne 3 ]] || [[ "$py_minor" -lt 10 ]] || [[ "$py_minor" -ge 12 ]]; then
        ui_error "Python $py_major.$py_minor detected, but Flink Agents requires Python >=3.10 and <3.12"
        return 1
    fi

    ui_success "Python $py_major.$py_minor found"
    return 0
}

show_install_plan() {
    ui_section "Install plan"
    ui_kv "OS" "$OS"
    ui_kv "Flink version" "$FLINK_VERSION"
    ui_kv "Flink Agents version" "$FLINK_AGENTS_VERSION"
    ui_kv "Install Flink" "$INSTALL_FLINK"
    ui_kv "Install directory" "$INSTALL_DIR"
    ui_kv "Enable PyFlink" "$ENABLE_PYFLINK"
    if [[ "$ENABLE_PYFLINK" == "yes" ]] || [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        ui_kv "Venv directory" "$VENV_DIR"
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

    if [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        if python -c "import flink_agents; print('flink-agents', flink_agents.__version__)" 2>/dev/null; then
            ui_success "flink-agents Python package verified"
        else
            ui_error "flink-agents Python package import failed"
            return 1
        fi

        local common_jar_found=0
        for jar in "$FLINK_HOME/lib"/flink-agents-dist-common-*.jar; do
            [[ -f "$jar" ]] && common_jar_found=1 && break
        done
        if [[ "$common_jar_found" -eq 1 ]]; then
            ui_success "flink-agents-dist-common JAR found in FLINK_HOME/lib"
        else
            ui_error "flink-agents-dist-common JAR not found in $FLINK_HOME/lib"
            return 1
        fi

        local thin_jar_found=0
        for jar in "$FLINK_HOME/lib"/flink-agents-dist-flink-*-thin.jar; do
            [[ -f "$jar" ]] && thin_jar_found=1 && break
        done
        if [[ "$thin_jar_found" -eq 1 ]]; then
            ui_success "flink-agents-dist thin JAR found in FLINK_HOME/lib"
        else
            ui_error "flink-agents-dist thin JAR not found in $FLINK_HOME/lib"
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
                INSTALL_FLINK=yes
                shift
                ;;
            --enable-pyflink|--enable-pyFlink)
                ENABLE_PYFLINK=yes
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

    show_install_plan

    if [[ "$DRY_RUN" == "1" ]]; then
        ui_success "Dry run complete (no changes made)"
        return 0
    fi

    ui_stage "Installing Apache Flink"
    install_flink_if_needed
    resolve_flink_home

    ui_stage "Setting up Python environment"
    if should_enable_pyflink; then
        PYFLINK_ACTUALLY_ENABLED=1
        copy_pyflink_jar
        setup_python_env
        copy_flink_agents_jars
    else
        ui_info "Skipping PyFlink and Python package setup (ENABLE_PYFLINK=${ENABLE_PYFLINK})."
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

