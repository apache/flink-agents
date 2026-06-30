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

import org.apache.flink.agents.plan.condition.ActionSelector;
import org.apache.flink.agents.plan.condition.ActionSelector.ConditionExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for referenced-attribute extraction performed during condition compilation. */
class ReferencedAttributeKeyExtractionTest {

    private static Set<String> referencedAttributeKeys(String source) {
        ActionSelector selector = ActionSelector.classify(source);
        assertThat(selector).isInstanceOf(ConditionExpression.class);
        return ConditionExpressionCompiler.compile((ConditionExpression) selector)
                .referencedTopLevelAttributeKeys();
    }

    static Stream<Arguments> staticKeyCases() {
        return Stream.of(
                Arguments.of("has(score)", "score"),
                Arguments.of("attributes.score > 50", "score"),
                Arguments.of("attributes.output.region > 1", "output"),
                Arguments.of("region > 10", "region"),
                Arguments.of("size(score_name) > 1", "score_name"),
                Arguments.of("'k' in score_name", "score_name"),
                Arguments.of("score_name == {}", "score_name"),
                Arguments.of("score_name['batch-3.12'].region > 10", "score_name"),
                Arguments.of("has(a.b)", "a"),
                Arguments.of("id == 'x'", "id"),
                Arguments.of("attributes['score'] > 1", "score"),
                Arguments.of("attributes['a.b.c'] == 7", "a.b.c"),
                Arguments.of("'a.b.c' in attributes", "a.b.c"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("staticKeyCases")
    void extractsStaticKey(String source, String expectedKey) {
        assertThat(referencedAttributeKeys(source)).containsExactly(expectedKey);
    }

    @Test
    void dynamicTopLevelIndexRejected() {
        assertThatThrownBy(() -> referencedAttributeKeys("attributes[region_id] > 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole 'attributes' map");
    }

    @Test
    void wholeAttributesUseRejected() {
        assertThatThrownBy(() -> referencedAttributeKeys("attributes == {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole 'attributes' map");
        assertThatThrownBy(() -> referencedAttributeKeys("size(attributes) > 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole 'attributes' map");
    }

    @Test
    void literalKeyNamedAttributesKept() {
        // A user attribute that happens to be named "attributes" is a normal key, not the
        // whole-map sentinel, and must not be rejected.
        assertThat(referencedAttributeKeys("attributes['attributes'] > 1"))
                .containsExactly("attributes");
        assertThat(referencedAttributeKeys("attributes.attributes > 1"))
                .containsExactly("attributes");
    }

    @Test
    void repeatedStaticKeyIsExtractedOnce() {
        assertThat(referencedAttributeKeys("attributes['a.b.c'] == 7 && 'a.b.c' in attributes"))
                .containsExactly("a.b.c");
    }

    @Test
    void dynamicMembershipInAttributesRejected() {
        assertThatThrownBy(() -> referencedAttributeKeys("key_name in attributes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole 'attributes' map");
    }
}
