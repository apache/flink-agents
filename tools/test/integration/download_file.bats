#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
}

@test "download_file: curl is invoked with the exact expected argv" {
    DOWNLOADER=curl
    shim_bin curl

    download_file "https://example.test/x.tgz" "$BATS_TEST_TMPDIR/out"

    [ "$(shim_call_count curl)" = "1" ]
    local got
    got="$(shim_calls curl)"
    local expected
    expected=$'-fL\t--progress-bar\t--proto\t=https\t--tlsv1.2\t--retry\t3\t--max-time\t600\t--retry-delay\t1\t--retry-connrefused\t-o\t'"$BATS_TEST_TMPDIR/out"$'\thttps://example.test/x.tgz'
    [ "$got" = "$expected" ]
}

@test "download_file: curl argv contains no stray '--' token (PR #599 regression guard)" {
    DOWNLOADER=curl
    shim_bin curl

    download_file "https://example.test/x.tgz" "$BATS_TEST_TMPDIR/out"

    local got
    got="$(shim_calls curl)"
    # A bare '--' token would appear as tab-bracketed or at line edges.
    # Use POSIX [ ] with string-prefix stripping: if the pattern is absent,
    # stripping it leaves got unchanged (strings are equal → [ ] succeeds).
    # bash 3.2 does not trigger set -e for [[ ]] failures, so [ ] is required.
    [ "${got#*$'\t--\t'}" = "$got" ]
    [ "${got%$'\t--'}" = "$got" ]
    [ "${got#'--'}" = "$got" ]
}

@test "download_file: curl failure propagates" {
    DOWNLOADER=curl
    shim_bin curl 22

    run download_file "https://example.test/x.tgz" "$BATS_TEST_TMPDIR/out"
    [ "$status" -ne 0 ]
}

@test "download_file: wget is invoked with the exact expected argv" {
    DOWNLOADER=wget
    shim_bin wget

    download_file "https://example.test/x.tgz" "$BATS_TEST_TMPDIR/out"

    [ "$(shim_call_count wget)" = "1" ]
    local got
    got="$(shim_calls wget)"
    local expected
    expected=$'-q\t--show-progress\t--https-only\t--secure-protocol=TLSv1_2\t--tries=3\t--timeout=600\t-O\t'"$BATS_TEST_TMPDIR/out"$'\thttps://example.test/x.tgz'
    [ "$got" = "$expected" ]
}

@test "detect_downloader: picks curl when available" {
    DOWNLOADER=""
    shim_bin curl
    shim_bin wget
    detect_downloader
    [ "$DOWNLOADER" = "curl" ]
}

@test "detect_downloader: falls back to wget when curl is missing" {
    DOWNLOADER=""
    shim_bin_missing curl
    shim_bin wget
    detect_downloader
    [ "$DOWNLOADER" = "wget" ]
}

@test "detect_downloader: dies when neither curl nor wget is available" {
    DOWNLOADER=""
    shim_bin_missing curl
    shim_bin_missing wget
    run detect_downloader
    [ "$status" -ne 0 ]
    # Use POSIX [ ] for set -e compatibility on bash 3.2 (see also Test 2).
    [ "${output#*Missing downloader}" != "$output" ]
}
