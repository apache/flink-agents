#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

if [ "$#" -gt 2 ]; then
  echo "Usage: $0 [<old_ref> [<new_ref>]]" >&2
  exit 1
fi

log_range=()
if [ "$#" -eq 1 ]; then
  log_range=("${1}..HEAD")
elif [ "$#" -eq 2 ]; then
  log_range=("${1}..${2}")
fi

{ git log --pretty=format:'%an' "${log_range[@]}"; echo; git log --pretty=format:%B "${log_range[@]}" | awk '/^[Cc]o-authored-by:/ { sub(/[^:]+:/, ""); sub(/^ */, ""); sub(/ <[^>]+>$/, ""); print }'; } | sort -u | awk 'NR==1{printf "%s", $0; next} {printf ", %s", $0} END{print ""}'
