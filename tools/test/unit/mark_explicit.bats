#!/usr/bin/env bats

setup() {
    load '../helpers/load'
    load_install_sh
    reset_install_sh_state
}

@test "mark_explicit: unset variable yields _EXPLICIT=0" {
    unset MY_VAR MY_VAR_EXPLICIT
    mark_explicit MY_VAR
    [ "$MY_VAR_EXPLICIT" = "0" ]
}

@test "mark_explicit: empty variable yields _EXPLICIT=0" {
    MY_VAR=""
    mark_explicit MY_VAR
    [ "$MY_VAR_EXPLICIT" = "0" ]
}

@test "mark_explicit: non-empty variable yields _EXPLICIT=1" {
    MY_VAR="something"
    mark_explicit MY_VAR
    [ "$MY_VAR_EXPLICIT" = "1" ]
}

@test "mark_explicit: zero-string non-empty is _EXPLICIT=1" {
    MY_VAR="0"
    mark_explicit MY_VAR
    [ "$MY_VAR_EXPLICIT" = "1" ]
}
