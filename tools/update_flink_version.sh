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

set -e

# List of files that need to be updated
POM_FILES=(
    "e2e-test/flink-agents-end-to-end-tests-integration/pom.xml"
    "dist/pom.xml"
)

if [ -z "${TARGET_FLINK_VERSION:-}" ]; then
    echo "TARGET_FLINK_VERSION was not set."
    echo "Usage: TARGET_FLINK_VERSION=x.y.z ./update_flink_version.sh"
    echo "Example: TARGET_FLINK_VERSION=2.2.1 ./update_flink_version.sh"
    exit 1
fi

# Validate version format (x.y.z)
if ! [[ "$TARGET_FLINK_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: TARGET_FLINK_VERSION must be in x.y.z format (e.g., 2.2.1)"
    echo "Got: $TARGET_FLINK_VERSION"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Cross-platform sed -i
sedi() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

update_pom_property() {
    local file=$1 key=$2 value=$3
    sedi "s|<${key}>.*</${key}>|<${key}>${value}</${key}>|g" "$file"
}

MINOR_VERSION="${TARGET_FLINK_VERSION%.*}"

echo -e "${YELLOW}Updating flink.${MINOR_VERSION}.version to ${TARGET_FLINK_VERSION}...${NC}"
for pom_file in "${POM_FILES[@]}"; do
    update_pom_property "$PROJECT_ROOT/$pom_file" "flink.${MINOR_VERSION}.version" "$TARGET_FLINK_VERSION"
    echo -e "${GREEN}✓ Updated $pom_file${NC}"
done

# Update default version if major.minor matches
current_default=$(sed -n 's/.*<flink.version>\(.*\)<\/flink.version>.*/\1/p' "$PROJECT_ROOT/pom.xml" 2>/dev/null | head -1)
if [[ "${current_default%.*}" = "$MINOR_VERSION" ]]; then
    echo -e "${YELLOW}Updating default flink.version to ${TARGET_FLINK_VERSION}...${NC}"
    update_pom_property "$PROJECT_ROOT/pom.xml" "flink.version" "$TARGET_FLINK_VERSION"
    echo -e "${GREEN}✓ Updated default flink.version${NC}"
fi

echo -e "${GREEN}✓ Flink version update completed!${NC}"