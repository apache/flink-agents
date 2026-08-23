# Suite-level assertion that the interpreter running the test bodies is bash
# 4.1 or newer. Wired in explicitly by run.sh via --setup-suite-file, because
# auto-discovery probes the directory named by each path argument and would
# resolve to unit/ instead.
#
# This backs up the PATH pin in run.sh rather than replacing it: bats starts
# each test process through `#!/usr/bin/env bash`, so if the pin ever stops
# resolving, the run would otherwise continue under whatever bash PATH finds.
#
# A failing setup_suite aborts the run before any test body executes. Use
# `return 1`, not `exit 1`, which bats reports as "`exit 1' failed with
# status 0".

setup_suite() {
    if (( 10 * BASH_VERSINFO[0] + BASH_VERSINFO[1] < 41 )); then
        echo "ERROR: bats is running the test bodies under bash ${BASH_VERSION}," >&2
        echo "but this suite requires bash >= 4.1." >&2
        echo "Start the suite through tools/test/run.sh, which pins the interpreter" >&2
        echo "for the whole run. If it did start there, the pin is not resolving:" >&2
        echo "inspect tools/test/.bats-cache/shim/bash." >&2
        return 1
    fi
}
