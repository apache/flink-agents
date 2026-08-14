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

# NOTE: This script is adapted from the Apache Spark project.


validate_rat_jar() {
  local jar_cmd

  if command -v unzip >/dev/null 2>&1; then
    unzip -tq "$JAR" >/dev/null 2>&1
    return $?
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
    jar_cmd="$JAVA_HOME/bin/jar"
  elif command -v jar >/dev/null 2>&1; then
    jar_cmd="$(command -v jar)"
  else
    printf "Cannot validate Apache RAT: install a JDK with 'jar' or install 'unzip'.\n" >&2
    return 2
  fi

  "$jar_cmd" tf "$JAR" >/dev/null 2>&1
}

acquire_rat_jar() {
  local downloaded=false validation_status

  URL="https://repo.maven.apache.org/maven2/org/apache/rat/apache-rat/${RAT_VERSION}/apache-rat-${RAT_VERSION}.jar"
  JAR="$rat_jar"

  if [ ! -f "$JAR" ]; then
    downloaded=true
    printf "Attempting to fetch rat\n"
    JAR_DL="${JAR}.part"
    rm -f "$JAR_DL"
    if command -v curl >/dev/null 2>&1; then
      if ! curl --fail --silent --show-error --location --output "$JAR_DL" "$URL"; then
        rm -f "$JAR_DL"
        printf "Failed to download Apache RAT from %s.\n" "$URL" >&2
        return 1
      fi
    elif command -v wget >/dev/null 2>&1; then
      if ! wget --no-verbose --output-document="$JAR_DL" "$URL"; then
        rm -f "$JAR_DL"
        printf "Failed to download Apache RAT from %s.\n" "$URL" >&2
        return 1
      fi
    else
      printf "Cannot download Apache RAT: install 'curl' or 'wget'.\n" >&2
      return 1
    fi
    if ! mv "$JAR_DL" "$JAR"; then
      rm -f "$JAR_DL"
      printf "Failed to store the downloaded Apache RAT JAR at %s.\n" "$JAR" >&2
      return 1
    fi
  fi

  validate_rat_jar
  validation_status=$?
  if [ "$validation_status" -eq 2 ]; then
    if [ "$downloaded" = true ]; then
      rm -f "$JAR"
      printf "Cannot validate the downloaded Apache RAT JAR: install jar or unzip.\n" >&2
      return 1
    fi
    printf "Warning: cannot validate cached Apache RAT JAR at %s; install jar or unzip.\n" "$JAR" >&2
    return 0
  elif [ "$validation_status" -ne 0 ]; then
    rm -f "$JAR"
    printf "The Apache RAT JAR at %s is invalid.\n" "$JAR" >&2
    return 1
  fi
}

if [[ "${CHECK_LICENSE_SOURCE_ONLY:-}" == "1" ]]; then
  return 0
fi

# Go to the project root directory
FWDIR="$(cd "`dirname "$0"`"/..; pwd)"
cd "$FWDIR"

if [ -n "${JAVA_HOME:-}" ] && test -x "$JAVA_HOME/bin/java"; then
    declare java_cmd="$JAVA_HOME/bin/java"
else
    declare java_cmd=java
fi

export RAT_VERSION=0.16.1
export rat_jar="$FWDIR"/lib/apache-rat-${RAT_VERSION}.jar
mkdir -p "$FWDIR"/lib

acquire_rat_jar || {
    echo "Unable to acquire a valid RAT JAR at $rat_jar"
    exit 1
}

mkdir -p build
$java_cmd -jar "$rat_jar" --scan-hidden-directories -E "$FWDIR"/tools/.rat-excludes -d "$FWDIR" > build/rat-results.txt

if [ $? -ne 0 ]; then
   echo "RAT exited abnormally"
   exit 1
fi

ERRORS="$(cat build/rat-results.txt | grep -e "??")"

if test ! -z "$ERRORS"; then
    echo "Could not find Apache license headers in the following files:"
    echo "$ERRORS"
    exit 1
else
    echo -e "RAT checks passed."
fi
