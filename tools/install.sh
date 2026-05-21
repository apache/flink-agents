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

# -E (errtrace) propagates the ERR trap into functions and command
# substitutions so the failure banner fires from anywhere in the script,
# not only at the top level.
set -Eeuo pipefail

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
# Some interactive read combinations (notably `read -e` under stdin redirection)
# can swallow SIGINT, leaving the user pressing Ctrl+C with no effect. Install
# an explicit INT trap so Ctrl+C always lands.
trap 'die_cancelled' INT

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
        curl -fL --progress-bar --proto '=https' --tlsv1.2 --retry 3 --max-time 900 --retry-delay 1 --retry-connrefused -o "$output" "$url"
        return
    fi
    wget -q --show-progress --https-only --secure-protocol=TLSv1_2 --tries=3 --timeout=900 -O "$output" "$url"
}

GUM_VERSION="${FLINK_AGENTS_GUM_VERSION:-0.17.0}"
GUM=""
GUM_STATUS="skipped"
GUM_REASON=""
# Persistent cache for the auto-downloaded gum binary so reruns don't
# re-download it. Honors XDG_CACHE_HOME, falls back to ~/.cache.
GUM_CACHE_ROOT="${FLINK_AGENTS_GUM_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/flink-agents/gum}"

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

    local cache_dir="${GUM_CACHE_ROOT}/${GUM_VERSION}"
    local cached_gum="${cache_dir}/gum"
    if [[ -x "$cached_gum" ]]; then
        GUM="$cached_gum"
        GUM_STATUS="cached"
        GUM_REASON="reused from ${cache_dir}"
        return 0
    fi

    if ! command -v tar >/dev/null 2>&1; then
        GUM_REASON="tar not found"
        return 1
    fi

    local os arch asset base gum_tmpdir extracted_path
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

    extracted_path="$(find "$gum_tmpdir" -type f -name gum 2>/dev/null | head -n1 || true)"
    if [[ -z "$extracted_path" ]]; then
        GUM_REASON="gum binary missing after extract"
        return 1
    fi

    chmod +x "$extracted_path" >/dev/null 2>&1 || true
    if [[ ! -x "$extracted_path" ]]; then
        GUM_REASON="gum binary is not executable"
        return 1
    fi

    # Promote into the persistent cache so subsequent runs skip the download.
    if mkdir -p "$cache_dir" 2>/dev/null && mv "$extracted_path" "$cached_gum" 2>/dev/null; then
        GUM="$cached_gum"
        GUM_REASON="cached at ${cache_dir}"
    else
        # Cache write failed (read-only HOME etc.) — fall back to using the
        # extracted binary directly. It'll get cleaned up at EXIT, costing a
        # re-download next run, but the current run still works.
        GUM="$extracted_path"
        GUM_REASON="temp, verified (cache unavailable)"
    fi
    GUM_STATUS="installed"
    return 0
}

print_gum_status() {
    case "$GUM_STATUS" in
        found)
            ui_success "gum available (${GUM_REASON})"
            ;;
        cached)
            ui_success "gum loaded from cache (v${GUM_VERSION})"
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
    # Write to stderr so the message survives `$(...)` command substitution
    # — otherwise the cancellation note would be silently captured by the
    # caller's variable and the user would see nothing on Ctrl+C.
    ui_info "Cancelled by user" >&2
    exit 130
}

# Fires on any command that exits non-zero under set -e — the cases that
# would otherwise dump a tail of pip / curl / tar noise and silently exit.
# `die`/`die_cancelled` use plain `exit` (not a non-zero command), so they
# do NOT trip this trap; they print their own friendly message and exit
# straight away. That keeps the banner reserved for genuinely unexpected
# failures.
on_error() {
    local rc=$1
    local line=$2
    local cmd=$3
    # Suppress ourselves if we re-enter (e.g. echo failing under set -e
    # inside the handler itself).
    trap - ERR
    {
        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        if (( INSTALL_STAGE_CURRENT > 0 )); then
            printf '%b Installation failed at stage %d/%d (%s).\n' \
                "${ERROR}✗${NC}" \
                "$INSTALL_STAGE_CURRENT" "$INSTALL_STAGE_TOTAL" \
                "$INSTALL_STAGE_TITLE"
        else
            printf '%b Installation failed.\n' "${ERROR}✗${NC}"
        fi
        echo ""
        echo "  Command:   ${cmd}"
        echo "  Source:    install.sh:${line}"
        echo "  Exit code: ${rc}"
        echo ""
        echo "  Re-run with --verbose for full output, or report at:"
        echo "    https://github.com/apache/flink-agents/issues"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    } >&2
    exit "$rc"
}
trap 'on_error $? $LINENO "$BASH_COMMAND"' ERR

INSTALL_STAGE_TOTAL=5
INSTALL_STAGE_CURRENT=0
INSTALL_STAGE_TITLE=""

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
    # Remember the stage title so on_error can include it in the
    # failure banner ("Installation failed at stage 3/5 (Installing
    # Apache Flink)").
    INSTALL_STAGE_TITLE="$title"
    ui_section "[${INSTALL_STAGE_CURRENT}/${INSTALL_STAGE_TOTAL}] ${title}"
}

UI_KV_KEY_WIDTH=24

ui_kv() {
    local key="$1"
    local value="$2"
    local labeled="${key}:"
    if [[ -n "$GUM" ]]; then
        local key_part value_part
        key_part="$("$GUM" style --foreground "#5a6480" --width "$UI_KV_KEY_WIDTH" "$labeled")"
        value_part="$("$GUM" style --bold "$value")"
        "$GUM" join --horizontal "$key_part" "$value_part"
    else
        printf "${MUTED}%-${UI_KV_KEY_WIDTH}s${NC} %s\n" "$labeled" "$value"
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
mark_explicit FLINK_AGENTS_VERSION
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

# Mirrors https://flink.apache.org/downloads/#apache-flink-agents
# (latest first). Note: 0.1.x only ships JARs for Flink 1.20, while 0.2.x
# ships JARs for Flink 1.20 / 2.0 / 2.1 / 2.2 — see the download page for
# the exact compatibility matrix.
FLINK_AGENTS_SUPPORTED_VERSIONS=("0.2.1" "0.2.0" "0.1.1" "0.1.0")
FLINK_AGENTS_RECOMMENDED_VERSION="0.2.1"

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
  --flink-version <ver>   Apache Flink version (e.g. 2.2.0); overrides the interactive picker
  --flink-agents-version <ver>
                          Flink Agents version (default: ${FLINK_AGENTS_RECOMMENDED_VERSION}); overrides the picker
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
#
# Implementation note: the folding steps use sed instead of bash
# `${var//pattern/replacement}`. Bash 3.2 (macOS /bin/bash) does not
# strip the backslash from an escaped slash in the replacement, so
# `${p//\/.\//\/}` leaves a literal `\/` in the result (concretely:
# normalize_path "./" under PWD=/tmp/x produced /tmp/x\ on macOS).
# sed sidesteps the parameter-expansion quirk entirely.
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
    p="$(printf '%s' "$p" | sed -E 's|/+|/|g; s|/\./|/|g; s|/\.$||; s|/+$||')"
    [[ -z "$p" ]] && p='/'
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

prompt_flink_agents_version_interactive() {
    if ! is_promptable; then
        return 1
    fi

    local labels=()
    local v
    for v in "${FLINK_AGENTS_SUPPORTED_VERSIONS[@]}"; do
        if [[ "$v" == "$FLINK_AGENTS_RECOMMENDED_VERSION" ]]; then
            labels+=("$v (recommended)")
        else
            labels+=("$v")
        fi
    done

    local selection=""
    if [[ -n "$GUM" ]] && gum_is_tty; then
        local _rc=0
        selection="$("$GUM" choose \
            --header "Select Flink Agents version" \
            --cursor-prefix "❯ " \
            "${labels[@]}" < /dev/tty)" || _rc=$?
        (( _rc == 0 )) || die_cancelled
        selection="${selection%% *}"
    else
        printf 'Select Flink Agents version:\n' > /dev/tty
        local i=1
        for v in "${FLINK_AGENTS_SUPPORTED_VERSIONS[@]}"; do
            local suffix=""
            [[ "$v" == "$FLINK_AGENTS_RECOMMENDED_VERSION" ]] && suffix=" (recommended)"
            printf '  %d) %s%s\n' "$i" "$v" "$suffix" > /dev/tty
            i=$((i+1))
        done
        local answer=""
        printf 'Enter choice [1-%d, default %s]: ' \
            "${#FLINK_AGENTS_SUPPORTED_VERSIONS[@]}" "$FLINK_AGENTS_RECOMMENDED_VERSION" > /dev/tty
        read -r answer < /dev/tty || die_cancelled
        if [[ "$answer" =~ ^[0-9]+$ ]] \
           && (( answer >= 1 && answer <= ${#FLINK_AGENTS_SUPPORTED_VERSIONS[@]} )); then
            selection="${FLINK_AGENTS_SUPPORTED_VERSIONS[$((answer-1))]}"
        fi
    fi

    if [[ -z "$selection" ]]; then
        selection="$FLINK_AGENTS_RECOMMENDED_VERSION"
    fi
    FLINK_AGENTS_VERSION="$selection"
    return 0
}

# Populate FLINK_VERSION from an existing FLINK_HOME. Tries to parse
# `lib/flink-dist-<ver>.jar` first because it's instant; falls back to
# Extract the major.minor portion of a Flink version string.
#   2.2.0           -> 2.2
#   2.2.0-SNAPSHOT  -> 2.2
#   2.2-SNAPSHOT    -> 2.2     (local source builds, no patch number)
#   2.1-rc1         -> 2.1
# Returns empty string on no match. Note: replaces the older
# `${FLINK_VERSION%.*}` trick, which silently produced "2" for "2.2-SNAPSHOT".
flink_major_minor() {
    printf '%s' "$1" | sed -E -n 's/^([0-9]+\.[0-9]+).*/\1/p'
}

# True (rc=0) when the version string carries a pre-release suffix
# (-SNAPSHOT, -rc1, -dev, -beta-2, ...). PyPI only carries finished
# release wheels for apache-flink, so we need to know when to fall
# back from `==exact` to `~=X.Y.0` (compatible release).
is_snapshot_version() {
    local v="${1:-}"
    [[ -n "$v" && "$v" == *-* ]]
}

# `bin/flink --version`, which is authoritative but spins up a JVM and can
# take 3-10s on cold start. Returns 0 on success.
detect_flink_version_from_home() {
    [[ -n "${FLINK_HOME:-}" && -d "$FLINK_HOME" ]] || return 1

    # A Flink version on the wire is roughly:
    #   <num>.<num>(.<num>)?(-<suffix>)?
    # Suffix examples: SNAPSHOT, rc1, beta-2, 20251115. Anchor it loose
    # enough to handle local source builds (flink-dist-2.2-SNAPSHOT.jar).
    local ver_re='[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9._]+)?'
    local version=""

    # Fast path: filename inspection. Avoids JVM startup entirely.
    local jar
    for jar in "$FLINK_HOME/lib"/flink-dist-[0-9]*.jar; do
        [[ -f "$jar" ]] || continue
        version="$(basename "$jar" | sed -E -n "s/^flink-dist-(${ver_re})\.jar$/\1/p")"
        [[ -n "$version" ]] && break
    done

    # Slow path: ask the Flink CLI. JVM startup means a few seconds of
    # silence, so signal what's happening.
    if [[ -z "$version" && -x "$FLINK_HOME/bin/flink" ]]; then
        ui_info "Detecting Flink version (running '${FLINK_HOME}/bin/flink --version', this may take a few seconds)..."
        local out
        out="$("$FLINK_HOME/bin/flink" --version 2>/dev/null || true)"
        version="$(printf '%s\n' "$out" | sed -E -n "s/.*Version:[[:space:]]*(${ver_re}).*/\1/p" | head -n1)"
    fi

    if [[ -z "$version" ]]; then
        return 1
    fi

    FLINK_VERSION="$version"
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
        FLINK_MAJOR_MINOR="$(flink_major_minor "$FLINK_VERSION")"
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
    FLINK_MAJOR_MINOR="$(flink_major_minor "$FLINK_VERSION")"
}

plan_flink_agents() {
    if [[ "$FLINK_AGENTS_VERSION_EXPLICIT" -eq 0 ]]; then
        prompt_flink_agents_version_interactive || true
    fi
}

# Classify a candidate VENV_DIR path. Echoes one of:
#   new       — path doesn't exist; caller should mkdir + python -m venv
#   empty     — empty directory; safe to use as venv root
#   venv      — already a real Python venv (pyvenv.cfg marker present)
#   nonempty  — directory contains foreign files; caller MUST re-prompt
#   file      — path is a regular file or invalid; caller MUST re-prompt
# Exit code: 0 for the safe-to-use cases, 1 for the must-re-prompt cases.
#
# We only treat pyvenv.cfg as the venv marker — `bin/activate` alone is
# not enough since arbitrary projects sometimes ship a file by that name.
validate_venv_dir() {
    local p="$1"
    if [[ -z "$p" ]]; then
        printf 'file'
        return 1
    fi
    if [[ ! -e "$p" ]]; then
        printf 'new'
        return 0
    fi
    if [[ ! -d "$p" ]]; then
        printf 'file'
        return 1
    fi
    if [[ -f "$p/pyvenv.cfg" ]]; then
        printf 'venv'
        return 0
    fi
    # Directory exists. Empty iff no entries including dotfiles.
    local entries
    entries="$(find "$p" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null || true)"
    if [[ -z "$entries" ]]; then
        printf 'empty'
        return 0
    fi
    printf 'nonempty'
    return 1
}

# Drive the "Choose Python venv directory" picker, then loop until the
# user picks a path that's either non-existent, empty, or an actual
# Python venv. Sets the global VENV_DIR. Caller must already have
# ensured we're in an interactive shell (is_promptable).
prompt_and_validate_venv_dir() {
    local seed_default="$1"
    VENV_DIR="$(prompt_path_choice_interactive \
        "Choose Python venv directory" \
        "$seed_default" \
        "/path/to/venv")"
    VENV_DIR="$(normalize_path "$VENV_DIR")"
    local kind
    while true; do
        kind="$(validate_venv_dir "$VENV_DIR")" || true
        case "$kind" in
            new|empty|venv) return 0 ;;
            nonempty)
                ui_warn "${VENV_DIR} already exists and is not a Python venv. Pick a different path, or remove its contents and try again."
                ;;
            file|*)
                ui_warn "${VENV_DIR} is not a directory. Pick a different path."
                ;;
        esac
        VENV_DIR="$(prompt_path_input \
            "Enter Python venv directory" \
            "/path/to/venv")"
        VENV_DIR="$(normalize_path "$VENV_DIR")"
    done
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

    # Validate VENV_DIR before doing anything Python-related — if the
    # user picked a bad path we want to bail before we make them resolve
    # a Python interpreter, and certainly before stage 3 downloads Flink.
    if [[ "$VENV_DIR_EXPLICIT" -eq 0 ]] && is_promptable; then
        prompt_and_validate_venv_dir "$VENV_DIR"
    else
        VENV_DIR="$(normalize_path "$VENV_DIR")"
        # No prompt available (--non-interactive / NO_PROMPT / explicit
        # VENV_DIR). Refuse to scribble into a foreign directory; tell
        # the caller exactly what's wrong instead of silently mixing the
        # venv into their codebase.
        local kind
        kind="$(validate_venv_dir "$VENV_DIR")" || true
        case "$kind" in
            new|empty|venv) ;;
            nonempty)
                die "VENV_DIR=${VENV_DIR} already exists and is not a Python venv. Set VENV_DIR to a new or empty path, or to an existing venv."
                ;;
            file|*)
                die "VENV_DIR=${VENV_DIR} is not a directory."
                ;;
        esac
    fi

    resolve_python
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
# Quote a value for safe re-sourcing by the parent shell via single-quoted
# assignment. Any embedded single quotes are escaped by closing/reopening
# the quoted string.
edit_plan_quote() {
    local v="$1"
    printf "'%s'" "${v//\'/\'\\\'\'}"
}

# Write the subset of plan variables we may have modified to a sourceable
# state file. Called from inside the edit subshell on a successful action.
edit_plan_dump_state() {
    local out="$1"
    {
        printf 'INSTALL_FLINK=%s\n'             "$(edit_plan_quote "$INSTALL_FLINK")"
        printf 'FLINK_VERSION=%s\n'             "$(edit_plan_quote "$FLINK_VERSION")"
        printf 'INSTALL_DIR=%s\n'               "$(edit_plan_quote "$INSTALL_DIR")"
        printf 'FLINK_HOME=%s\n'                "$(edit_plan_quote "${FLINK_HOME:-}")"
        printf 'FLINK_MAJOR_MINOR=%s\n'         "$(edit_plan_quote "${FLINK_MAJOR_MINOR:-}")"
        printf 'ENABLE_PYFLINK=%s\n'            "$(edit_plan_quote "$ENABLE_PYFLINK")"
        printf 'PYFLINK_ACTUALLY_ENABLED=%s\n'  "$(edit_plan_quote "$PYFLINK_ACTUALLY_ENABLED")"
        printf 'VENV_DIR=%s\n'                  "$(edit_plan_quote "$VENV_DIR")"
        printf 'PYTHON_BIN=%s\n'                "$(edit_plan_quote "${PYTHON_BIN:-}")"
        printf 'FLINK_AGENTS_VERSION=%s\n'      "$(edit_plan_quote "$FLINK_AGENTS_VERSION")"
    } > "$out"
}

# Show a menu of currently-applicable plan fields and re-prompt for the
# selected one. The entire body runs inside a `()` subshell so that an
# ESC anywhere — top-level menu or any nested sub-prompt — only terminates
# this menu (subshell exits 130, caller treats it as "back"). Successful
# edits dump their state to a file that the caller sources back.
# Ctrl+C is delivered to the whole process group; the parent's INT trap
# fires die_cancelled and exits the installer before we ever inspect $rc.
edit_plan_interactive() {
    local state_file
    state_file="$(mktempfile)"

    # `set -e` is active, so we MUST catch any non-zero exit from the
    # subshell with `|| rc=$?` — otherwise the script would terminate the
    # moment a sub-prompt's ESC bubbles `exit 130` up through the subshell,
    # and the parent would never get to interpret it as "back".
    local rc=0
    (
        local labels=()
        local actions=()

        labels+=("Flink Agents version: $FLINK_AGENTS_VERSION")
        actions+=("flink_agents_version")

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

        local picked_index=-1
        if [[ -n "$GUM" ]] && gum_is_tty; then
            local _rc=0
            local selected=""
            selected="$("$GUM" choose \
                --header "Edit a setting  (↑/↓ navigate · Enter select · Esc to go back)" \
                --cursor-prefix "❯ " \
                "${labels[@]}" < /dev/tty)" || _rc=$?
            # ESC inside this menu — exit the subshell so the caller treats
            # it as "back to confirm". The exit code we use here (130) is
            # what `gum` returns on Esc; we just propagate it.
            if (( _rc != 0 )); then
                exit "$_rc"
            fi
            local i=0
            for l in "${labels[@]}"; do
                if [[ "$l" == "$selected" ]]; then
                    picked_index=$i
                    break
                fi
                i=$((i+1))
            done
        else
            printf 'Edit a setting (blank or "b" to go back):\n' > /dev/tty
            local i=1
            for l in "${labels[@]}"; do
                printf '  %d) %s\n' "$i" "$l" > /dev/tty
                i=$((i+1))
            done
            local answer=""
            printf 'Enter choice [1-%d]: ' "${#labels[@]}" > /dev/tty
            read -r answer < /dev/tty || die_cancelled
            case "$answer" in
                ""|b|B|back|0) exit 130 ;;
            esac
            if [[ "$answer" =~ ^[0-9]+$ ]] && (( answer >= 1 && answer <= ${#labels[@]} )); then
                picked_index=$((answer - 1))
            fi
        fi

        if (( picked_index < 0 )); then
            # No valid selection — same as back.
            exit 130
        fi

        local action="${actions[$picked_index]}"
        case "$action" in
            flink_agents_version)
                prompt_flink_agents_version_interactive || true
                ;;
            install_flink)
                if choose_install_method_interactive "Install Flink?"; then
                    if [[ "$INSTALL_FLINK" != "Yes" ]]; then
                        INSTALL_FLINK=Yes
                        # Coming from No → Yes: caller had no version/dir, so
                        # walk them through both immediately instead of
                        # forcing two more menu trips.
                        prompt_flink_version_interactive || true
                        INSTALL_DIR="$(prompt_path_choice_interactive \
                            "Choose Flink install directory" \
                            "$INSTALL_DIR" \
                            "/path/to/flink-install-dir")"
                        INSTALL_DIR="$(normalize_path "$INSTALL_DIR")"
                    fi
                    FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
                else
                    INSTALL_FLINK=No
                    edit_prompt_flink_home
                    detect_flink_version_from_home || ui_warn "Could not auto-detect Flink version; keeping ${FLINK_VERSION}"
                fi
                FLINK_MAJOR_MINOR="$(flink_major_minor "$FLINK_VERSION")"
                ;;
            flink_version)
                prompt_flink_version_interactive || true
                FLINK_HOME="${INSTALL_DIR}/flink-${FLINK_VERSION}"
                FLINK_MAJOR_MINOR="$(flink_major_minor "$FLINK_VERSION")"
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
                        prompt_and_validate_venv_dir "$VENV_DIR"
                    fi
                else
                    ENABLE_PYFLINK=No
                fi
                ;;
            venv_dir)
                prompt_and_validate_venv_dir "$VENV_DIR"
                ;;
        esac

        edit_plan_dump_state "$state_file"
    ) || rc=$?

    if (( rc != 0 )); then
        # Any non-zero exit from the subshell means we never reached the
        # state dump — treat it as "back to confirm". (Ctrl+C would have
        # killed the parent before this point via the INT trap.)
        return 0
    fi

    # shellcheck disable=SC1090
    source "$state_file"
}

confirm_install_plan() {
    if [[ "$NO_PROMPT" == "1" ]] || ! is_promptable; then
        return 0
    fi
    while true; do
        local action
        # Capture stdout AND honor the child's exit code. The child runs in a
        # command-substitution subshell, which does NOT inherit the parent's
        # `trap die_cancelled INT`. When Ctrl+C lands inside `gum choose`,
        # the subshell exits 130 but its stdout is empty — without this `||`,
        # the case-statement falls through to the default arm and we'd loop
        # forever re-prompting instead of exiting.
        action="$(confirm_plan_action_interactive)" || exit 130
        case "$action" in
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
    # Placeholder kept for backward compatibility with the previous signature;
    # readline does not surface placeholders the way `gum input` did, so we
    # weave the hint into the prompt label instead.
    local placeholder="${2:-}"
    local input=""
    printf '%s\n' "$header" > /dev/tty
    if [[ -n "$placeholder" ]]; then
        printf '  (example: %s)\n' "$placeholder" > /dev/tty
    fi
    IFS= read -e -r -p "  path> " input < /dev/tty || die_cancelled
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
    printf '%s\n' "$header" > /dev/tty
    IFS= read -e -r -p "  path> " input < /dev/tty || die_cancelled

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

    # PyPI only ships finished release wheels for apache-flink, so a
    # source-built FLINK_VERSION like "2.1-SNAPSHOT" / "2.0.0-rc1" has
    # no matching distribution. Fall back to the compatible-release
    # operator (~= X.Y.0 ↔ >=X.Y.0,<X.(Y+1)) for these cases — the
    # user's Java JARs are still the source build, but PyFlink will
    # come from the nearest released minor on PyPI.
    local apache_flink_spec="apache-flink==${FLINK_VERSION}"
    if is_snapshot_version "$FLINK_VERSION"; then
        apache_flink_spec="apache-flink~=${FLINK_MAJOR_MINOR}.0"
        ui_warn "FLINK_VERSION=${FLINK_VERSION} is a pre-release build; installing ${apache_flink_spec} from PyPI instead."
    fi

    ui_info "Installing Python packages (may take a few minutes)..."
    pip_install_quiet \
        "flink-agents==${FLINK_AGENTS_VERSION}" \
        "$apache_flink_spec"
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

    # Use `tr` for case-folding instead of `${var,,}` — the latter is
    # bash 4+ only, and macOS still ships /bin/bash 3.2.
    local expected_lc actual_lc
    expected_lc="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')"
    actual_lc="$(printf '%s' "$actual"   | tr '[:upper:]' '[:lower:]')"
    if [[ "$expected_lc" != "$actual_lc" ]]; then
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
    ui_section "Environment (read-only)"
    ui_kv "OS" "$OS"
    local java_summary="not found"
    if command -v java >/dev/null 2>&1; then
        local jv
        jv="$(java -version 2>&1 | head -n1 | sed -E 's/^[^ ]+ version "?([^"]+)"?.*/\1/')"
        java_summary="${jv:-detected}"
    fi
    ui_kv "Java" "$java_summary"
    if [[ -n "${JAVA_HOME:-}" ]]; then
        ui_kv "JAVA_HOME" "$JAVA_HOME"
    else
        ui_kv "JAVA_HOME" "<not set, Flink will auto-detect>"
    fi
    local python_summary="<none>"
    if [[ -n "$PYTHON_BIN" ]] && command -v "$PYTHON_BIN" >/dev/null 2>&1; then
        local pv
        pv="$("$PYTHON_BIN" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")' 2>/dev/null || true)"
        python_summary="${PYTHON_BIN}${pv:+ ($pv)}"
    elif command -v python3 >/dev/null 2>&1; then
        local pv
        pv="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")' 2>/dev/null || true)"
        python_summary="python3${pv:+ ($pv)}"
    fi
    ui_kv "Python" "$python_summary"

    ui_section "Installation plan"
    ui_kv "Flink Agents version" "$FLINK_AGENTS_VERSION"
    ui_kv "Install Flink" "$INSTALL_FLINK"
    if [[ "$INSTALL_FLINK" == "Yes" ]]; then
        ui_kv "Flink version" "$FLINK_VERSION"
        ui_kv "Install directory" "$INSTALL_DIR"
    else
        if [[ -n "${FLINK_HOME:-}" ]]; then
            ui_kv "FLINK_HOME" "$FLINK_HOME (v$FLINK_VERSION)"
        fi
    fi
    ui_kv "Enable PyFlink" "$ENABLE_PYFLINK"
    if [[ "$ENABLE_PYFLINK" == "Yes" ]] || [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        ui_kv "Venv directory" "$VENV_DIR"
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
            --flink-agents-version)
                if [[ $# -lt 2 ]]; then
                    die "--flink-agents-version requires a version argument"
                fi
                FLINK_AGENTS_VERSION="$2"
                FLINK_AGENTS_VERSION_EXPLICIT=1
                shift 2
                ;;
            --flink-agents-version=*)
                FLINK_AGENTS_VERSION="${1#*=}"
                FLINK_AGENTS_VERSION_EXPLICIT=1
                shift
                ;;
            --flink-version)
                if [[ $# -lt 2 ]]; then
                    die "--flink-version requires a version argument"
                fi
                FLINK_VERSION="$2"
                FLINK_VERSION_EXPLICIT=1
                shift 2
                ;;
            --flink-version=*)
                FLINK_VERSION="${1#*=}"
                FLINK_VERSION_EXPLICIT=1
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
    plan_flink_agents

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
    echo ""
    ui_section "Next steps"
    ui_success "1) Point FLINK_HOME at this install (Flink CLI and clients read it):"
    ui_info  "       export FLINK_HOME=${FLINK_HOME}"
    if [[ "$PYFLINK_ACTUALLY_ENABLED" -eq 1 ]]; then
        ui_success "2) Activate the Python venv (PyFlink + flink-agents are installed there):"
        ui_info  "       source ${VENV_DIR}/bin/activate"
        ui_success "3) To make both permanent, append the two lines above to your shell rc"
        ui_info  "   (~/.zshrc or ~/.bashrc)."
    else
        ui_success "2) To make it permanent, append the line above to your shell rc"
        ui_info  "   (~/.zshrc or ~/.bashrc)."
    fi

    show_footer_links
}

if [[ "${FLINK_AGENTS_INSTALL_SH_NO_RUN:-0}" != "1" ]]; then
    parse_args "$@"
    configure_verbose
    main
fi

