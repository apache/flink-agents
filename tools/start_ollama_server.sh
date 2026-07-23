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
install_ollama() {
  attempts=3
  for attempt in $(seq 1 "$attempts"); do
    if curl -fsSL https://ollama.com/install.sh -o "$install_script" && sh "$install_script"; then
      return 0
    fi

    if [ "$attempt" -lt "$attempts" ]; then
      delay=$((attempt * 10))
      echo "ollama install attempt ${attempt}/${attempts} failed; retrying in ${delay}s" >&2
      sleep "$delay"
    fi
  done

  echo "ollama install failed after ${attempts} attempts" >&2
  return 1
}

install_ollama || exit 1

# llama-server ships one libggml-cpu-<microarch> build per instruction set and loads the
# best match for the host CPU. The hosted runner pool is mixed, so which kernels serve a
# request depends on the machine the job happens to land on. Log it, so a failure can be
# attributed to the microarchitecture that served it.
find_ollama_lib_dir() {
  for dir in /usr/local/lib/ollama /usr/lib/ollama /opt/ollama/lib; do
    if compgen -G "${dir}/libggml-cpu-*" > /dev/null 2>&1; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

cpu_model=$(sed -n 's/^model name[[:space:]]*: *//p' /proc/cpuinfo | head -1)
cpu_isa=""
for flag in avx2 avx512f avx512_bf16 amx_tile; do
  if grep -qm1 "^flags.*\b${flag}\b" /proc/cpuinfo; then
    cpu_isa="${cpu_isa}${cpu_isa:+,}${flag}"
  fi
done
echo "ollama-cpu: model='${cpu_model:-unknown}' isa='${cpu_isa:-none}'"

lib_dir=$(find_ollama_lib_dir) || lib_dir=""
if [ -z "$lib_dir" ]; then
  echo "ollama-cpu: no libggml-cpu-* builds found; leaving backend selection untouched"
  exit 0
fi

# Upstream reports llama-server segfaulting on hosts whose CPU exposes the newer vector
# extensions (ollama/ollama#17006). Every AVX-512-bearing build is removed so llama-server
# falls back to the AVX2 build, whose kernels are shared by every runner in the pool and do
# not crash. Keep this list in sync with ggml's GGML_CPU_ALL_VARIANTS x86 variants.
avx512_variants="skylakex cannonlake cascadelake icelake cooperlake zen4 sapphirerapids"

for variant in $avx512_variants; do
  for build in "$lib_dir"/libggml-cpu-"$variant".*; do
    [ -e "$build" ] || continue
    sudo mv "$build" "${build}.disabled"
    echo "ollama-cpu: disabled $(basename "$build")"
  done
done

echo "ollama-cpu: remaining builds:"
for build in "$lib_dir"/libggml-cpu-*; do
  case "$build" in
    *.disabled) continue ;;
  esac
  echo "ollama-cpu:   $(basename "$build")"
done

# The installer runs ollama as a systemd unit, so restart it to drop any backend the
# running process already mapped.
if systemctl is-active --quiet ollama; then
  sudo systemctl restart ollama
  echo "ollama-cpu: restarted ollama"
fi
