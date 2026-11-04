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
package org.apache.flink.agents.runtime.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class LegacyFlinkConfParserTest {
    @TempDir private File tmpDir;

    @Test
    void testDuplicateKeyOverridesEarlierValue() throws IOException {
        assertThat(parse("key: first", "key: second")).containsOnly(entry("key", "second"));
    }

    @Test
    void testValueContainingColonSpaceIsKeptWhole() throws IOException {
        assertThat(parse("key: a: b: c")).containsOnly(entry("key", "a: b: c"));
    }

    @Test
    void testCommentsAreDropped() throws IOException {
        assertThat(parse("# full-line comment", "key: value # trailing comment", "other: a#b"))
                .containsOnly(entry("key", "value"), entry("other", "a"));
    }

    @Test
    void testLinesWithoutKeyValuePairAreSkipped() throws IOException {
        assertThat(parse("key:value", "nested:", "key-only", "valid: yes"))
                .containsOnly(entry("valid", "yes"));
    }

    @Test
    void testEmptyKeyOrValueIsSkipped() throws IOException {
        assertThat(parse(": value", "key: ", "key2:   # only a comment", "valid: yes"))
                .containsOnly(entry("valid", "yes"));
    }

    @Test
    void testKeysAndValuesAreTrimmedAndKeptAsStrings() throws IOException {
        assertThat(parse("  key   :   1  ", "flag: true", "quoted: 'v'", "list: [a, b]"))
                .containsOnly(
                        entry("key", "1"),
                        entry("flag", "true"),
                        entry("quoted", "'v'"),
                        entry("list", "[a, b]"));
    }

    @Test
    void testIndentedLinesAreReadAsTopLevelKeys() throws IOException {
        assertThat(parse("agent:", "    key1: v1")).containsOnly(entry("key1", "v1"));
    }

    @Test
    void testMissingFileFails() {
        assertThatThrownBy(() -> LegacyFlinkConfParser.parse(new File(tmpDir, "missing.yaml")))
                .isInstanceOf(IOException.class);
    }

    private Map<String, String> parse(String... lines) throws IOException {
        File file = new File(tmpDir, "flink-conf.yaml");
        Files.write(file.toPath(), Arrays.asList(lines));
        return LegacyFlinkConfParser.parse(file);
    }
}
