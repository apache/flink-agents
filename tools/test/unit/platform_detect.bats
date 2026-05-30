#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load '../helpers/shim'
    load_install_sh
    reset_install_sh_state
    shim_setup
}

# Helper: install a fake `uname` that emits a chosen string for -s and -m.
fake_uname() {
    local sysname="$1" machine="$2"
    shim_bin_script uname "
case \"\$1\" in
    -s) echo '$sysname' ;;
    -m) echo '$machine' ;;
    *)  echo 'fake' ;;
esac
"
}

@test "gum_detect_os: Darwin" {
    fake_uname Darwin x86_64
    [ "$(gum_detect_os)" = "Darwin" ]
}

@test "gum_detect_os: Linux" {
    fake_uname Linux x86_64
    [ "$(gum_detect_os)" = "Linux" ]
}

@test "gum_detect_os: unknown kernel" {
    fake_uname FreeBSD x86_64
    [ "$(gum_detect_os)" = "unsupported" ]
}

@test "gum_detect_arch: x86_64 stays x86_64" {
    fake_uname Linux x86_64
    [ "$(gum_detect_arch)" = "x86_64" ]
}

@test "gum_detect_arch: amd64 maps to x86_64" {
    fake_uname Linux amd64
    [ "$(gum_detect_arch)" = "x86_64" ]
}

@test "gum_detect_arch: arm64 stays arm64" {
    fake_uname Darwin arm64
    [ "$(gum_detect_arch)" = "arm64" ]
}

@test "gum_detect_arch: aarch64 maps to arm64" {
    fake_uname Linux aarch64
    [ "$(gum_detect_arch)" = "arm64" ]
}

@test "gum_detect_arch: i686 maps to i386" {
    fake_uname Linux i686
    [ "$(gum_detect_arch)" = "i386" ]
}

@test "gum_detect_arch: armv7l maps to armv7" {
    fake_uname Linux armv7l
    [ "$(gum_detect_arch)" = "armv7" ]
}

@test "gum_detect_arch: unknown machine is unknown" {
    fake_uname Linux riscv64
    [ "$(gum_detect_arch)" = "unknown" ]
}

@test "detect_os_or_die: macOS via OSTYPE" {
    OSTYPE="darwin23"
    detect_os_or_die
    [ "$OS" = "macos" ]
}

@test "detect_os_or_die: linux via OSTYPE" {
    OSTYPE="linux-gnu"
    detect_os_or_die
    [ "$OS" = "linux" ]
}

@test "detect_os_or_die: WSL via WSL_DISTRO_NAME" {
    OSTYPE="something-unknown"
    WSL_DISTRO_NAME="Ubuntu"
    detect_os_or_die
    [ "$OS" = "linux" ]
}

@test "detect_os_or_die: unsupported OSTYPE dies" {
    OSTYPE="cygwin"
    unset WSL_DISTRO_NAME
    run detect_os_or_die
    [ "$status" -ne 0 ]
    [[ "$output" == *"Unsupported operating system"* ]]
}
