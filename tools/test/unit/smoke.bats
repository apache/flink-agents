#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "install.sh sources without executing main" {
    [ "$(type -t parse_args)" = "function" ]
    [ "$(type -t download_file)" = "function" ]
}

@test "reset_install_sh_state restores defaults" {
    NO_PROMPT=1
    reset_install_sh_state
    [ "$NO_PROMPT" = "0" ]
}
