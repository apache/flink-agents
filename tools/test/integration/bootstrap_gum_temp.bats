#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
    # Force interactive-shell + tty path so bootstrap doesn't auto-skip.
    NO_PROMPT=0
    # gum_is_tty falls back to checking /dev/tty readability; bats provides one.
    # If the test env happens not to, override via TERM/NO_COLOR being unset.
    unset NO_COLOR
    TERM=xterm
    # Make sure `gum` isn't already on PATH so we exercise the install branch.
    shim_bin_missing gum
    # Provide tar so the early "tar not found" branch is skipped.
    shim_bin tar
    # Real uname is fine here — we just need a supported os/arch.

    # Override is_non_interactive_shell to report "interactive" by default so
    # tests that exercise the download path are not short-circuited by stdin/
    # stdout not being ttys in the bats process.
    is_non_interactive_shell() { return 1; }

    # Override gum_is_tty to report "has tty" by default so the second guard
    # in bootstrap_gum_temp does not short-circuit the download path either.
    gum_is_tty() { return 0; }
}

@test "bootstrap_gum_temp: non-interactive shell auto-skips with no downloads" {
    # Restore the real check and set NO_PROMPT to force non-interactive.
    is_non_interactive_shell() {
        [[ "${NO_PROMPT:-0}" == "1" ]]
    }
    NO_PROMPT=1
    run bootstrap_gum_temp
    [ "$status" -ne 0 ]
    [ "$GUM" = "" ]
    [ "$GUM_STATUS" = "skipped" ]
    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count wget)" = "0" ]
}

@test "bootstrap_gum_temp: download failure sets reason='download failed'" {
    DOWNLOADER=curl
    shim_bin curl 22
    bootstrap_gum_temp || true
    [ "$GUM" = "" ]
    [ "$GUM_STATUS" = "skipped" ]
    [ "$GUM_REASON" = "download failed" ]
}

@test "bootstrap_gum_temp: checksum failure sets reason='checksum unavailable or failed'" {
    DOWNLOADER=curl
    # curl always succeeds (both asset and checksums.txt downloads succeed)
    shim_bin curl
    # sha256sum / shasum both fail
    shim_bin sha256sum 1
    shim_bin shasum 1
    bootstrap_gum_temp || true
    [ "$GUM_REASON" = "checksum unavailable or failed" ]
}

@test "bootstrap_gum_temp: extract failure sets reason='extract failed'" {
    DOWNLOADER=curl
    shim_bin curl
    shim_bin sha256sum
    shim_bin shasum
    shim_bin tar 1
    bootstrap_gum_temp || true
    [ "$GUM_REASON" = "extract failed" ]
}

@test "bootstrap_gum_temp: missing gum binary after extract sets reason" {
    DOWNLOADER=curl
    shim_bin curl
    shim_bin sha256sum
    shim_bin shasum
    # tar 'succeeds' but produces nothing
    shim_bin tar
    bootstrap_gum_temp || true
    [ "$GUM_REASON" = "gum binary missing after extract" ]
}

@test "bootstrap_gum_temp: gum already on PATH is reported as found" {
    # Clear the missing-names list so command -v gum sees the shim on PATH.
    SHIM_MISSING_NAMES=()
    shim_bin gum
    bootstrap_gum_temp
    [ "$GUM" = "gum" ]
    [ "$GUM_STATUS" = "found" ]
}

@test "bootstrap_gum_temp: successful download+extract sets GUM_STATUS=installed" {
    DOWNLOADER=curl
    # curl writes a dummy archive to the -o path
    shim_bin_script curl '
out=""; prev=""
for a in "$@"; do
    [[ "$prev" == "-o" ]] && out="$a"
    prev="$a"
done
[[ -n "$out" ]] && printf "fake" > "$out"
'
    shim_bin sha256sum
    shim_bin shasum
    # tar extracts a fake gum binary into the temp dir
    shim_bin_script tar '
if [[ "$1" == "-xzf" ]]; then
    dest="$4"
    mkdir -p "$dest"
    printf "#!/bin/bash\n" > "$dest/gum"
    chmod +x "$dest/gum"
fi
'
    bootstrap_gum_temp
    [ "$GUM_STATUS" = "installed" ]
    [ -n "$GUM" ]
    [ -x "$GUM" ]
}

@test "bootstrap_gum_temp: cached binary on disk is reused without downloading" {
    DOWNLOADER=curl
    # Pre-seed the persistent cache with an executable gum.
    mkdir -p "${GUM_CACHE_ROOT}/${GUM_VERSION}"
    printf '#!/bin/bash\n' > "${GUM_CACHE_ROOT}/${GUM_VERSION}/gum"
    chmod +x "${GUM_CACHE_ROOT}/${GUM_VERSION}/gum"
    # Any downloader call would be a regression — fail loudly if it happens.
    shim_bin curl 22
    shim_bin wget 22

    bootstrap_gum_temp
    [ "$GUM_STATUS" = "cached" ]
    [ "$GUM" = "${GUM_CACHE_ROOT}/${GUM_VERSION}/gum" ]
    [ -x "$GUM" ]
    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count wget)" = "0" ]
}

@test "bootstrap_gum_temp: successful install promotes binary into the cache" {
    DOWNLOADER=curl
    shim_bin_script curl '
out=""; prev=""
for a in "$@"; do
    [[ "$prev" == "-o" ]] && out="$a"
    prev="$a"
done
[[ -n "$out" ]] && printf "fake" > "$out"
'
    shim_bin sha256sum
    shim_bin shasum
    shim_bin_script tar '
if [[ "$1" == "-xzf" ]]; then
    dest="$4"
    mkdir -p "$dest"
    printf "#!/bin/bash\n" > "$dest/gum"
    chmod +x "$dest/gum"
fi
'
    bootstrap_gum_temp
    [ "$GUM_STATUS" = "installed" ]
    [ -x "${GUM_CACHE_ROOT}/${GUM_VERSION}/gum" ]
    [ "$GUM" = "${GUM_CACHE_ROOT}/${GUM_VERSION}/gum" ]
}
