#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
    FLINK_HOME="$BATS_TEST_TMPDIR/flink-home"
    mkdir -p "$FLINK_HOME/lib"
    FLINK_VERSION="2.2.0"
    FLINK_MAJOR_MINOR="2.2"
    FLINK_AGENTS_VERSION="0.2.1"
    FLINK_AGENTS_BASE_URL="https://mirror.test/flink"
    FLINK_AGENTS_CHECKSUM_BASE_URL="https://downloads.test/flink"
}

# A curl shim that writes whatever was requested at -o into a fake file
# whose body is "fake-jar-<basename>" — enough to satisfy the "non-empty"
# check, and lets us verify which URLs were requested.
configure_curl_shim() {
    shim_bin_script curl '
out=""; url=""; prev=""
for a in "$@"; do
    [[ "$prev" == "-o" ]] && out="$a"
    case "$a" in -*|"") ;; *) url="$a" ;; esac
    prev="$a"
done
[[ -n "$out" ]] && printf "fake-content-%s" "$(basename "$out")" > "$out"
'
}

@test "flink_agents_jar_relpath: builds expected ASF mirror path" {
    FLINK_MAJOR_MINOR="2.1"
    FLINK_AGENTS_VERSION="0.2.0"
    run flink_agents_jar_relpath
    [ "$status" -eq 0 ]
    [ "$output" = "flink-agents-0.2.0/flink-agents-dist-flink-2.1-0.2.0.jar" ]
}

@test "install_flink_agents_jar: downloads from mirror, verifies, lands in FLINK_HOME/lib" {
    DOWNLOADER=curl
    configure_curl_shim

    run install_flink_agents_jar
    [ "$status" -eq 0 ]
    [ -f "$FLINK_HOME/lib/flink-agents-dist-flink-2.2-0.2.1.jar" ]

    # Two curl calls expected: jar from mirror, sha512 sidecar from downloads.apache.org.
    case "$(shim_calls curl)" in
        *"https://mirror.test/flink/flink-agents-0.2.1/flink-agents-dist-flink-2.2-0.2.1.jar"*) ;;
        *) false ;;
    esac
    case "$(shim_calls curl)" in
        *"https://downloads.test/flink/flink-agents-0.2.1/flink-agents-dist-flink-2.2-0.2.1.jar.sha512"*) ;;
        *) false ;;
    esac
}

@test "install_flink_agents_jar: reuses existing JAR, skips re-download" {
    DOWNLOADER=curl
    configure_curl_shim
    # Pre-seed the target JAR.
    : > "$FLINK_HOME/lib/flink-agents-dist-flink-2.2-0.2.1.jar"

    run install_flink_agents_jar
    [ "$status" -eq 0 ]
    # Only the sha512 sidecar should have been fetched, never the JAR itself.
    case "$(shim_calls curl)" in
        *"flink-agents-dist-flink-2.2-0.2.1.jar"*"flink-agents-dist-flink-2.2-0.2.1.jar"*) false ;;
        *) ;;
    esac
}

@test "install_flink_agents_jar: empty downloaded file is a hard error" {
    DOWNLOADER=curl
    # Shim that "succeeds" but writes nothing.
    shim_bin_script curl '
out=""; prev=""
for a in "$@"; do
    [[ "$prev" == "-o" ]] && out="$a"
    prev="$a"
done
[[ -n "$out" ]] && : > "$out"
'
    run install_flink_agents_jar
    [ "$status" -ne 0 ]
    case "$output" in *"empty file"*) ;; *) false ;; esac
    [ ! -f "$FLINK_HOME/lib/flink-agents-dist-flink-2.2-0.2.1.jar" ]
}

@test "install_flink_agents_jar: download failure surfaces a clean error" {
    DOWNLOADER=curl
    shim_bin curl 22  # curl exit 22 = HTTP error
    run install_flink_agents_jar
    [ "$status" -ne 0 ]
    case "$output" in *"Failed to download flink-agents JAR"*) ;; *) false ;; esac
}
