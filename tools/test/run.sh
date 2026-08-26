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

# Bash 4.1 or newer is required: on older bash, `set -e` does not trigger on a
# failing `[[ ]]` and the ERR trap does not fire for it, so many of the substring
# assertions in this suite would silently pass. That changed in 4.1, so 4.0 is
# rejected as well; macOS ships 3.2 at /bin/bash. Force a clean failure here
# rather than mislead developers.
#
# The gate below binds this shell only. bats evaluates each test body in a
# separate process started through `#!/usr/bin/env bash`, so the interpreter is
# re-resolved from PATH at every hop. Two further steps cover that: the PATH pin
# below points `bash` at the interpreter this gate accepted, and
# helpers/bash_version_guard.bash re-checks the version from inside the run.
#
# The empty-BASH_VERSION test must stay first: it short-circuits, so the
# arithmetic is never evaluated under a shell that has no BASH_VERSINFO.
if [ -z "${BASH_VERSION:-}" ] || (( 10 * BASH_VERSINFO[0] + BASH_VERSINFO[1] < 41 )); then
    echo "ERROR: bash >= 4.1 required (detected: ${BASH_VERSION:-unknown})." >&2
    echo "macOS ships bash 3.2 at /bin/bash; install bash 4.1+ via Homebrew:" >&2
    echo "    brew install bash" >&2
    echo "Then run with the new bash, e.g.:" >&2
    echo "    /opt/homebrew/bin/bash $0" >&2
    exit 1
fi

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$HERE/.bats-cache"

clone_pinned() {
    local name="$1" url="$2" tag="$3"
    if [[ ! -d "$CACHE/$name" ]]; then
        echo "Fetching $name@$tag" >&2
        git clone --quiet --depth 1 --branch "$tag" "$url" "$CACHE/$name"
    fi
}

mkdir -p "$CACHE"

# Pin the interpreter bats resolves, by putting a `bash` symlink to this shell
# ahead of everything else on PATH. $BASH is the interpreter the gate above
# accepted. $CACHE is gitignored, so the symlink does not show up in git status.
#
# This also re-interprets the scripts under test, which start with
# `#!/usr/bin/env bash` themselves: while the pin is in place the suite runs
# them under this interpreter instead of the one the developer's own PATH
# selects.
#
# Build the link under a temp name and rename it into place. Renaming within a
# directory replaces the name in one step, so a lookup running concurrently
# sees either the old link or the new one; `ln -sfn` instead unlinks before it
# re-creates, leaving a window in which the name does not exist and the lookup
# falls through to the next PATH entry.
SHIM="$CACHE/shim"
mkdir -p "$SHIM"
# A directory here would swallow the rename instead of being replaced by it.
if [[ -d "$SHIM/bash" ]]; then rm -rf "$SHIM/bash"; fi
rm -f "$SHIM/bash.$$"
ln -s "$BASH" "$SHIM/bash.$$"
mv -f "$SHIM/bash.$$" "$SHIM/bash"
PATH="$SHIM:$PATH"
export PATH

clone_pinned bats-core    https://github.com/bats-core/bats-core.git    v1.11.0
clone_pinned bats-support https://github.com/bats-core/bats-support.git v0.3.0
clone_pinned bats-assert  https://github.com/bats-core/bats-assert.git  v2.1.0

export BATS_LIB_PATH="$CACHE"

exec "$CACHE/bats-core/bin/bats" \
    --setup-suite-file "$HERE/helpers/bash_version_guard.bash" \
    --recursive "$HERE/unit" "$HERE/integration"
