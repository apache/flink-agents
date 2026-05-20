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

package org.apache.flink.agents.api.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkillSourceSpecTest {

    @Test
    void schemeIsLowercased() {
        SkillSourceSpec spec = new SkillSourceSpec("LOCAL", Map.of("path", "/x"));
        assertEquals("local", spec.getScheme());
    }

    @Test
    void equalityIgnoresSchemeCase() {
        assertEquals(
                new SkillSourceSpec("local", Map.of("path", "/x")),
                new SkillSourceSpec("LOCAL", Map.of("path", "/x")));
    }

    @Test
    void equalityRequiresSameParams() {
        assertNotEquals(
                new SkillSourceSpec("local", Map.of("path", "/x")),
                new SkillSourceSpec("local", Map.of("path", "/y")));
    }

    @Test
    void hashCodeMatchesEquals() {
        SkillSourceSpec a = new SkillSourceSpec("local", Map.of("path", "/x"));
        SkillSourceSpec b = new SkillSourceSpec("LOCAL", Map.of("path", "/x"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillSourceSpec original =
                new SkillSourceSpec("package", Map.of("package", "p", "resource", "r"));
        String json = mapper.writeValueAsString(original);
        SkillSourceSpec restored = mapper.readValue(json, SkillSourceSpec.class);
        assertEquals(original, restored);
    }

    @Test
    void unknownSchemeDeserializesSuccessfully() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"scheme\":\"future-scheme\",\"params\":{\"k\":\"v\"}}";
        SkillSourceSpec spec = mapper.readValue(json, SkillSourceSpec.class);
        assertEquals("future-scheme", spec.getScheme());
        assertEquals(Map.of("k", "v"), spec.getParams());
    }
}
