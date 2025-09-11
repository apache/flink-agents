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

# only works on linux
os=$(uname -s)
echo $os
if [[ $os == "Linux" ]]; then
  curl -fsSL https://ollama.com/install.sh | sh
  ret=$?
  if [ "$ret" != "0" ]
  then
    exit $ret
  fi

  if curl -f http://localhost:11434/api/tags >/dev/null 2>&1; then
      echo "Ollama is already running"
  else
      echo "Starting Ollama server"
      ollama serve &
      sleep 10 # wait for ollama to start
  fi

  ollama pull all-minilm:22m
  ollama run all-minilm:22m
fi