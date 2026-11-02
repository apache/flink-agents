/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.runtime.migration;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink Agents releases that have reference snapshots for migration tests.
 *
 * <p>Flink's {@code FlinkVersion} can't name Flink Agents releases, so migration tests are
 * parameterized over this enum instead. After a release, add its constant here and generate its
 * snapshots on the release branch with the {@code generate-migration-test-data} profile.
 */
public enum FlinkAgentsVersion {
    v0_4("0.4");

    private static final Pattern VERSION_PATTERN = Pattern.compile("v?(\\d+)[._](\\d+)");

    private final String versionStr;

    FlinkAgentsVersion(String versionStr) {
        this.versionStr = versionStr;
    }

    @Override
    public String toString() {
        return versionStr;
    }

    /** Returns every version with reference snapshots, oldest first. */
    public static List<FlinkAgentsVersion> publishedVersions() {
        return Arrays.asList(values());
    }

    /** Parses names like {@code 0.4}, {@code 0_4} or {@code v0_4}. */
    public static FlinkAgentsVersion parse(String name) {
        Matcher matcher = VERSION_PATTERN.matcher(name);
        if (matcher.matches()) {
            String versionStr = matcher.group(1) + "." + matcher.group(2);
            for (FlinkAgentsVersion version : values()) {
                if (version.versionStr.equals(versionStr)) {
                    return version;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown Flink Agents version "
                        + name
                        + ". Add it to FlinkAgentsVersion before generating its snapshots.");
    }
}
