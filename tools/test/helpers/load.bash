# Helpers used by every bats file. Loaded via `load 'helpers/load'`.

# Sources install.sh with the no-run hook so main() is skipped.
load_install_sh() {
    export FLINK_AGENTS_INSTALL_SH_NO_RUN=1
    # shellcheck disable=SC1090
    source "${BATS_TEST_DIRNAME}/../../install.sh"
    # Note: install.sh sets `set -euo pipefail`, which matches the
    # strict mode bats itself enables. Leave it on so bats's ERR trap
    # can detect failed assertions. Use `run <cmd>` (which captures
    # $status in a subshell) for tests that exercise failure paths.
}

# Resets every module-level variable install.sh defines, so tests don't
# leak state into one another.
reset_install_sh_state() {
    TMPFILES=()
    INSTALL_STAGE_CURRENT=0
    GUM=""
    GUM_STATUS="skipped"
    GUM_REASON=""
    DOWNLOADER=""
    HELP=0
    DRY_RUN=0
    NO_PROMPT=0
    VERBOSE=0
    INSTALL_FLINK="Ask"
    ENABLE_PYFLINK="Ask"
    PYTHON_BIN=""
    PYFLINK_ACTUALLY_ENABLED=0
    RECREATE_VENV=0
    FLINK_VERSION="2.2.0"
    FLINK_AGENTS_VERSION="0.3.0"
    FLINK_SCALA_VERSION="2.12"
    FLINK_BASE_URL="https://dlcdn.apache.org/flink"
    FLINK_SUPPORTED_VERSIONS=("2.2.0" "2.1.1" "2.0.1" "1.20.3")
    FLINK_RECOMMENDED_VERSION="2.2.0"
    INSTALL_DIR="$HOME/.local/flink"
    VENV_DIR=".flink-agents-env"
    GUM_VERSION="0.17.0"
    # Default the bootstrap cache to a per-test directory so we never touch
    # the developer's real $HOME/.cache when running locally.
    GUM_CACHE_ROOT="${BATS_TEST_TMPDIR:-/tmp}/gum-cache"
    FLINK_VERSION_EXPLICIT=0
    FLINK_AGENTS_VERSION_EXPLICIT=0
    INSTALL_DIR_EXPLICIT=0
    VENV_DIR_EXPLICIT=0
    FLINK_AGENTS_SUPPORTED_VERSIONS=("0.3.0" "0.2.1" "0.2.0" "0.1.1" "0.1.0")
    FLINK_AGENTS_RECOMMENDED_VERSION="0.3.0"
}
