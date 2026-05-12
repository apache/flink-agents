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

package org.apache.flink.agents.runtime.eventlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTruncatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testStringTruncation() {
        JsonTruncator truncator = new JsonTruncator(10, 0, 0);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", "This is a very long string that exceeds the limit");

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        assertThat(node.get("content").isObject()).isTrue();
        assertThat(node.get("content").get("truncatedString").asText()).isEqualTo("This is a ...");
        assertThat(node.get("content").get("omittedChars").asInt())
                .isEqualTo(49 - 10); // total length minus maxStringLength
    }

    @Test
    void testArrayTrimming() {
        JsonTruncator truncator = new JsonTruncator(0, 3, 0);
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode array = MAPPER.createArrayNode();
        for (int i = 0; i < 7; i++) {
            array.add("item" + i);
        }
        node.set("items", array);

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        assertThat(node.get("items").isObject()).isTrue();
        assertThat(node.get("items").get("truncatedList").size()).isEqualTo(3);
        assertThat(node.get("items").get("truncatedList").get(0).asText()).isEqualTo("item0");
        assertThat(node.get("items").get("truncatedList").get(1).asText()).isEqualTo("item1");
        assertThat(node.get("items").get("truncatedList").get(2).asText()).isEqualTo("item2");
        assertThat(node.get("items").get("omittedElements").asInt()).isEqualTo(4);
    }

    @Test
    void testDepthCollapsing() {
        JsonTruncator truncator = new JsonTruncator(0, 0, 2);
        ObjectNode node = MAPPER.createObjectNode();
        ObjectNode nested = MAPPER.createObjectNode();
        nested.put("scalarField", "value");
        nested.put("numberField", 42);
        ObjectNode deepNested = MAPPER.createObjectNode();
        deepNested.put("deep", "data");
        nested.set("nestedObj", deepNested);
        ArrayNode nestedArray = MAPPER.createArrayNode();
        nestedArray.add("a");
        nested.set("nestedArr", nestedArray);
        node.set("data", nested);

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        // The "data" field's nested object should be collapsed at depth 2
        assertThat(node.get("data").has("truncatedObject")).isTrue();
        ObjectNode truncatedObj = (ObjectNode) node.get("data").get("truncatedObject");
        assertThat(truncatedObj.get("scalarField").asText()).isEqualTo("value");
        assertThat(truncatedObj.get("numberField").asInt()).isEqualTo(42);
        assertThat(truncatedObj.has("nestedObj")).isFalse();
        assertThat(truncatedObj.has("nestedArr")).isFalse();
        assertThat(node.get("data").get("omittedFields").asInt()).isEqualTo(2);
    }

    @Test
    void testNoTruncationUnderLimits() {
        JsonTruncator truncator = new JsonTruncator(100, 10, 5);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("short", "hello");
        ArrayNode smallArray = MAPPER.createArrayNode();
        smallArray.add("a");
        smallArray.add("b");
        node.set("list", smallArray);

        boolean result = truncator.truncate(node);

        assertThat(result).isFalse();
        assertThat(node.get("short").asText()).isEqualTo("hello");
        assertThat(node.get("list").size()).isEqualTo(2);
    }

    @Test
    void testDisabledThresholds() {
        // All thresholds set to 0 — no truncation should occur
        JsonTruncator truncator = new JsonTruncator(0, 0, 0);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", "A".repeat(10000));
        ArrayNode bigArray = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            bigArray.add(i);
        }
        node.set("items", bigArray);
        ObjectNode deep = MAPPER.createObjectNode();
        deep.set("level2", MAPPER.createObjectNode().put("level3", "deep"));
        node.set("nested", deep);

        boolean result = truncator.truncate(node);

        assertThat(result).isFalse();
        assertThat(node.get("content").asText()).hasSize(10000);
        assertThat(node.get("items").size()).isEqualTo(100);
    }

    @Test
    void testProtectedFields() {
        JsonTruncator truncator = new JsonTruncator(5, 2, 2);
        ObjectNode node = MAPPER.createObjectNode();
        // Protected fields should never be truncated
        node.put("eventType", "org.apache.flink.agents.api.event.ChatRequestEvent");
        node.put("id", "a-very-long-identifier-that-exceeds-the-limit");
        ObjectNode attributes = MAPPER.createObjectNode();
        attributes.put("key", "value");
        attributes.set("nested", MAPPER.createObjectNode().put("deep", "data"));
        node.set("attributes", attributes);
        // Non-protected field should be truncated
        node.put("content", "This should be truncated");

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        // Protected fields remain untouched
        assertThat(node.get("eventType").asText())
                .isEqualTo("org.apache.flink.agents.api.event.ChatRequestEvent");
        assertThat(node.get("id").asText())
                .isEqualTo("a-very-long-identifier-that-exceeds-the-limit");
        assertThat(node.get("attributes").isObject()).isTrue();
        assertThat(node.get("attributes").get("key").asText()).isEqualTo("value");
        // Non-protected field is truncated
        assertThat(node.get("content").get("truncatedString")).isNotNull();
    }

    @Test
    void testCompositeScenario() {
        JsonTruncator truncator = new JsonTruncator(10, 2, 3);
        ObjectNode node = MAPPER.createObjectNode();
        // Long string — should trigger string truncation
        node.put("message", "Hello, this is a long message from the LLM");
        // Large array — should trigger array truncation
        ArrayNode tools = MAPPER.createArrayNode();
        for (int i = 0; i < 5; i++) {
            tools.add("tool" + i);
        }
        node.set("tools", tools);
        // Deep nesting — should trigger depth truncation at level 3
        ObjectNode level1 = MAPPER.createObjectNode();
        ObjectNode level2 = MAPPER.createObjectNode();
        level2.put("scalar", "kept");
        level2.set("tooDeep", MAPPER.createObjectNode().put("hidden", "gone"));
        level1.set("inner", level2);
        node.set("metadata", level1);

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        // String truncation applied
        assertThat(node.get("message").get("truncatedString")).isNotNull();
        assertThat(node.get("message").get("omittedChars").asInt()).isGreaterThan(0);
        // Array truncation applied
        assertThat(node.get("tools").get("truncatedList").size()).isEqualTo(2);
        assertThat(node.get("tools").get("omittedElements").asInt()).isEqualTo(3);
        // Depth truncation: level2 is at depth 3, so it should be collapsed
        ObjectNode metadataInner = (ObjectNode) node.get("metadata").get("inner");
        assertThat(metadataInner.has("truncatedObject")).isTrue();
        assertThat(metadataInner.get("truncatedObject").get("scalar").asText()).isEqualTo("kept");
        assertThat(metadataInner.get("omittedFields").asInt()).isEqualTo(1);
    }

    @Test
    void testRecursionSkipsDroppedTailElements() {
        // Regression test: when an array exceeds maxArrayElements, the elements beyond the cap
        // must NOT be recursed into. Prior to the fix, truncateArrayContents ran on the full
        // array before truncateArray dropped the tail, causing wasted CPU/allocations on
        // elements that were about to be discarded.
        JsonTruncator truncator = new JsonTruncator(5, 2, 0);
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();
        // Two short strings that fit under the retained cap and the string limit.
        items.add("a");
        items.add("b");
        // Three tail ObjectNodes whose long-string fields would be truncated if visited.
        String longValue = "this-string-is-much-longer-than-five-chars";
        ObjectNode tail0 = MAPPER.createObjectNode();
        tail0.put("payload", longValue);
        ObjectNode tail1 = MAPPER.createObjectNode();
        tail1.put("payload", longValue);
        ObjectNode tail2 = MAPPER.createObjectNode();
        tail2.put("payload", longValue);
        items.add(tail0);
        items.add(tail1);
        items.add(tail2);
        node.set("items", items);

        boolean result = truncator.truncate(node);

        assertThat(result).isTrue();
        // Retained list is exactly the first 2 elements; 3 are dropped.
        assertThat(node.get("items").get("truncatedList").size()).isEqualTo(2);
        assertThat(node.get("items").get("truncatedList").get(0).asText()).isEqualTo("a");
        assertThat(node.get("items").get("truncatedList").get(1).asText()).isEqualTo("b");
        assertThat(node.get("items").get("omittedElements").asInt()).isEqualTo(3);
        // The dropped tail ObjectNodes were never visited — their payload fields remain
        // untouched (still raw strings, not wrapped truncatedString objects). Under the old
        // pre-reorder code path, these would have been mutated before being discarded.
        assertThat(tail0.get("payload").isTextual()).isTrue();
        assertThat(tail0.get("payload").asText()).isEqualTo(longValue);
        assertThat(tail1.get("payload").isTextual()).isTrue();
        assertThat(tail1.get("payload").asText()).isEqualTo(longValue);
        assertThat(tail2.get("payload").isTextual()).isTrue();
        assertThat(tail2.get("payload").asText()).isEqualTo(longValue);
    }

    @Test
    void testNullNode() {
        JsonTruncator truncator = new JsonTruncator(10, 10, 10);

        boolean result = truncator.truncate(null);

        assertThat(result).isFalse();
    }
}
