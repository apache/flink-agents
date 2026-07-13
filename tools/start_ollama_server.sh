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
#################################################################################

# only works on linux
set -euo pipefail

os=$(uname -s)
echo "$os"

install_script=$(mktemp)
trap 'rm -f "$install_script"' EXIT

# The upstream installer probes for the .tar.zst asset with a silent HEAD request, and on
# any failure of that probe falls back to a .tgz that current releases no longer publish.
# A single transient network error therefore turns into a hard 404, so retry the install.
attempts=3
for attempt in $(seq 1 "$attempts"); do
  if curl -fsSL https://ollama.com/install.sh -o "$install_script" && sh "$install_script"; then
    exit 0
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    delay=$((attempt * 10))
    echo "ollama install attempt ${attempt}/${attempts} failed; retrying in ${delay}s" >&2
    sleep "$delay"
  fi
done

echo "ollama install failed after ${attempts} attempts" >&2
exit 1
