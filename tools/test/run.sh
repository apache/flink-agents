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

# Bash 4+ is required: bash 3.2 (macOS default) does not trigger `set -e`
# on `[[ ]]` failures or fire the ERR trap on them, which means many
# substring assertions in this suite would silently pass on bash 3.2.
# Force a clean failure here rather than mislead developers.
if [ -z "${BASH_VERSION:-}" ] || [ "${BASH_VERSION%%.*}" -lt 4 ]; then
    echo "ERROR: bash >= 4 required (detected: ${BASH_VERSION:-unknown})." >&2
    echo "macOS ships bash 3.2 at /bin/bash; install bash 4+ via Homebrew:" >&2
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
clone_pinned bats-core    https://github.com/bats-core/bats-core.git    v1.11.0
clone_pinned bats-support https://github.com/bats-core/bats-support.git v0.3.0
clone_pinned bats-assert  https://github.com/bats-core/bats-assert.git  v2.1.0

export BATS_LIB_PATH="$CACHE"

exec "$CACHE/bats-core/bin/bats" --recursive "$HERE/unit" "$HERE/integration"
