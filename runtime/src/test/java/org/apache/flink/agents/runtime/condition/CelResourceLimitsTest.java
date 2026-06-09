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

package org.apache.flink.agents.runtime.condition;

import org.apache.flink.agents.api.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for CEL runtime resource limits: PROGRAM_CACHE LRU + normalize depth cap. */
class CelResourceLimitsTest {

    @BeforeEach
    void clearCache() {
        CelExpressionFacade.clearProgramCacheForTests();
    }

    @Test
    void programCache_evictsAtMaxSize() {
        // Insert MAX_SIZE + 1 unique sources; cache must cap exactly at MAX_SIZE
        // (LinkedHashMap.removeEldestEntry is strict, unlike Caffeine's approximate TinyLFU).
        for (int i = 0; i <= CelExpressionFacade.PROGRAM_CACHE_MAX_SIZE; i++) {
            CelExpressionFacade.toProgram("attributes.field_" + i + " == " + i);
        }
        long size = CelExpressionFacade.programCacheSizeForTests();
        assertThat(size)
                .as(
                        "program cache must cap at exactly %d, got %d",
                        CelExpressionFacade.PROGRAM_CACHE_MAX_SIZE, size)
                .isEqualTo(CelExpressionFacade.PROGRAM_CACHE_MAX_SIZE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizeValue_preservesDeepJsonStringAsPlain() {
        // Build a nested map of depth (MAX + 2) with a JSON-shaped string at the bottom.
        String innerJson = "{\"deep_key\":\"deep_value\"}";
        Object inner = innerJson;
        for (int i = 0; i <= CelConditionEvaluator.MAX_NORMALIZE_DEPTH; i++) {
            Map<String, Object> wrap = new HashMap<>();
            wrap.put("wrap", inner);
            inner = wrap;
        }
        Event event = new Event("_input_event", (Map<String, Object>) inner);

        CelConditionEvaluator evaluator = new CelConditionEvaluator();
        Map<String, Object> activation = evaluator.createActivation(event);
        Map<String, Object> attrs = (Map<String, Object>) activation.get("attributes");

        Object cur = attrs;
        for (int i = 0; i <= CelConditionEvaluator.MAX_NORMALIZE_DEPTH; i++) {
            assertThat(cur).as("layer %d must still be a Map", i).isInstanceOf(Map.class);
            Map<String, Object> m = (Map<String, Object>) cur;
            cur = m.get("wrap");
        }
        assertThat(cur)
                .as("deep JSON string must NOT be parsed once past depth limit")
                .isInstanceOf(String.class)
                .isEqualTo(innerJson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizeValue_stillExpandsShallowJsonString() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("input", "{\"foo\":\"bar\",\"n\":42}");
        Event event = new Event("_input_event", attrs);

        CelConditionEvaluator evaluator = new CelConditionEvaluator();
        Map<String, Object> activation = evaluator.createActivation(event);
        Map<String, Object> activationAttrs = (Map<String, Object>) activation.get("attributes");
        Object input = activationAttrs.get("input");
        assertThat(input)
                .as("shallow JSON-shaped string must still be parsed into a Map")
                .isInstanceOf(Map.class);
    }
}
