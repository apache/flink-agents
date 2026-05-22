#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
    INSTALL_DIR="$BATS_TEST_TMPDIR/install"
    FLINK_VERSION="2.2.0"
    FLINK_SCALA_VERSION="2.12"
    FLINK_BASE_URL="https://example.test/flink"
}

# A tar shim that succeeds for both `-tzf` (validity check) and `-xzf` (extract).
# On extract it materializes a minimal Flink home with a lib/ dir.
configure_tar_shim() {
    shim_bin_script tar "
case \"\$1\" in
    -tzf) exit 0 ;;
    -xzf)
        # arg order: -xzf <archive> -C <dest>  (\$4 = dest)
        mkdir -p \"\$4/flink-${FLINK_VERSION}/lib\"
        ;;
    *) exit 0 ;;
esac
"
}

@test "install_flink_if_needed: INSTALL_FLINK=No uses pre-existing FLINK_HOME" {
    INSTALL_FLINK=No
    FLINK_HOME="$BATS_TEST_TMPDIR/preexisting"
    mkdir -p "$FLINK_HOME/lib"
    run install_flink_if_needed
    [ "$status" -eq 0 ]
    [ "$(shim_call_count curl)" = "0" ]
    [ "$(shim_call_count wget)" = "0" ]
}

@test "install_flink_if_needed: fresh install downloads then extracts" {
    INSTALL_FLINK=Yes
    DOWNLOADER=curl
    FLINK_HOME="$INSTALL_DIR/flink-$FLINK_VERSION"
    # curl shim writes a placeholder archive at the requested -o path.
    shim_bin_script curl "
# argv pattern: ... -o <output> <url>
out=\"\"
prev=\"\"
for a in \"\$@\"; do
    if [[ \"\$prev\" == \"-o\" ]]; then out=\"\$a\"; fi
    prev=\"\$a\"
done
[[ -n \"\$out\" ]] && printf 'fake tgz' > \"\$out\"
"
    configure_tar_shim

    run install_flink_if_needed
    [ "$status" -eq 0 ]
    [ "$(shim_call_count curl)" = "1" ]
    case "$(shim_calls curl)" in
        *"https://example.test/flink/flink-2.2.0/flink-2.2.0-bin-scala_2.12.tgz"*) ;;
        *) false ;;
    esac
    [ -d "$INSTALL_DIR/flink-$FLINK_VERSION/lib" ]
}

@test "install_flink_if_needed: existing valid archive is reused (no download)" {
    INSTALL_FLINK=Yes
    DOWNLOADER=curl
    mkdir -p "$INSTALL_DIR"
    # Pre-seed a "valid" archive — tar shim will report it as valid via -tzf.
    printf 'existing' > "$INSTALL_DIR/flink-${FLINK_VERSION}-bin-scala_${FLINK_SCALA_VERSION}.tgz"
    shim_bin curl
    configure_tar_shim
    FLINK_HOME="$INSTALL_DIR/flink-$FLINK_VERSION"

    run install_flink_if_needed
    [ "$status" -eq 0 ]
    [ "$(shim_call_count curl)" = "0" ]
}

@test "install_flink_if_needed: corrupt existing archive triggers re-download" {
    INSTALL_FLINK=Yes
    DOWNLOADER=curl
    mkdir -p "$INSTALL_DIR"
    local archive="$INSTALL_DIR/flink-${FLINK_VERSION}-bin-scala_${FLINK_SCALA_VERSION}.tgz"
    printf 'existing' > "$archive"
    # tar reports the existing archive as INVALID, then valid on the re-download.
    local state="$BATS_TEST_TMPDIR/tar_state"
    : > "$state"
    shim_bin_script tar "
case \"\$1\" in
    -tzf)
        if [[ ! -s '$state' ]]; then
            echo bad > '$state'
            exit 1
        fi
        exit 0
        ;;
    -xzf)
        mkdir -p \"\$4/flink-${FLINK_VERSION}/lib\"
        ;;
esac
"
    shim_bin_script curl "
out=\"\"; prev=\"\"
for a in \"\$@\"; do
    [[ \"\$prev\" == \"-o\" ]] && out=\"\$a\"
    prev=\"\$a\"
done
[[ -n \"\$out\" ]] && printf 'freshly downloaded' > \"\$out\"
"
    FLINK_HOME="$INSTALL_DIR/flink-$FLINK_VERSION"

    run install_flink_if_needed
    [ "$status" -eq 0 ]
    [ "$(shim_call_count curl)" = "1" ]
}

@test "install_flink_if_needed: incomplete extracted dir triggers re-extract" {
    INSTALL_FLINK=Yes
    DOWNLOADER=curl
    mkdir -p "$INSTALL_DIR/flink-${FLINK_VERSION}"  # missing lib/
    printf 'archive' > "$INSTALL_DIR/flink-${FLINK_VERSION}-bin-scala_${FLINK_SCALA_VERSION}.tgz"
    shim_bin curl
    configure_tar_shim
    FLINK_HOME="$INSTALL_DIR/flink-$FLINK_VERSION"

    run install_flink_if_needed
    [ "$status" -eq 0 ]
    [ -d "$INSTALL_DIR/flink-$FLINK_VERSION/lib" ]
}
