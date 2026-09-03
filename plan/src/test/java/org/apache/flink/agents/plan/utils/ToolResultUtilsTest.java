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

package org.apache.flink.agents.plan.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The result a sub-agent that declares a result type produces. */
    public static class Verdict {
        private boolean approved;
        private String note;
        private double score;

        public Verdict() {}

        public Verdict(boolean approved, String note, double score) {
            this.approved = approved;
            this.note = note;
            this.score = score;
        }

        public boolean isApproved() {
            return approved;
        }

        public void setApproved(boolean approved) {
            this.approved = approved;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }
    }

    @Test
    void normalizeReducesNestedContainersToPlainMapsAndLists() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("items", List.of(1, "two"));
        raw.put("node", MAPPER.createObjectNode().put("flag", true));

        Object normalized = ToolResultUtils.normalizeAgentResult(raw);

        assertThat(normalized)
                .isInstanceOf(Map.class)
                .isEqualTo(Map.of("items", List.of(1, "two"), "node", Map.of("flag", true)));
    }

    @Test
    void normalizeKeepsScalarsAndNull() {
        assertThat(ToolResultUtils.normalizeAgentResult("done")).isEqualTo("done");
        assertThat(ToolResultUtils.normalizeAgentResult(3)).isEqualTo(3);
        assertThat(ToolResultUtils.normalizeAgentResult(null)).isNull();
    }

    @Test
    void normalizeReportsThePathOfAValueJsonCannotExpress() {
        Map<String, Object> raw = Map.of("outer", List.of(new Object()));

        assertThatThrownBy(() -> ToolResultUtils.normalizeAgentResult(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result.outer[0]")
                .hasMessageContaining("java.lang.Object");
    }

    @Test
    void normalizeRejectsNonStringMapKeys() {
        assertThatThrownBy(
                        () ->
                                ToolResultUtils.normalizeAgentResult(
                                        Collections.singletonMap(1, "one")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map keys in sub-agent result must be strings at result");
    }

    @Test
    void normalizeRejectsNonFiniteNumbers() {
        assertThatThrownBy(
                        () ->
                                ToolResultUtils.normalizeAgentResult(
                                        Map.of("ratio", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Non-finite number in sub-agent result at result.ratio");

        assertThatThrownBy(() -> ToolResultUtils.normalizeAgentResult(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Non-finite number in sub-agent result at result");
    }

    @Test
    void normalizeRejectsAPojoCarriedInsideAJsonTree() {
        JsonNode tree =
                MAPPER.createObjectNode().set("wrapped", MAPPER.getNodeFactory().pojoNode(this));

        assertThatThrownBy(() -> ToolResultUtils.normalizeAgentResult(tree))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result.wrapped")
                .hasMessageContaining("POJONode is not supported");
    }

    @Test
    void normalizeWalksArrays() {
        Object normalized = ToolResultUtils.normalizeAgentResult(new int[] {1, 2});

        assertThat(normalized).isEqualTo(List.of(1, 2));

        assertThatThrownBy(() -> ToolResultUtils.normalizeAgentResult(new Object[] {new Object()}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result[0]");
    }

    /**
     * Declaring a result type is what makes a result JSON cannot express reportable: the type says
     * how to read it, and what comes out is only what the type declares.
     */
    @Test
    void aDeclaredResultTypeReadsAPojoIntoGenericForm() {
        Object normalized =
                ToolResultUtils.normalizeAgentResult(
                        new Verdict(true, "clean", 1.5), Verdict.class);

        assertThat(normalized).isEqualTo(Map.of("approved", true, "note", "clean", "score", 1.5));
    }

    @Test
    void aDeclaredResultTypeNarrowsAWiderResultToWhatItDeclares() {
        Object normalized =
                ToolResultUtils.normalizeAgentResult(
                        Map.of("approved", true, "note", "clean", "score", 1.5, "extra", 1),
                        Verdict.class);

        assertThat(normalized).isEqualTo(Map.of("approved", true, "note", "clean", "score", 1.5));
    }

    /** The undeclared path is unchanged: a POJO is still what it refuses. */
    @Test
    void anUndeclaredResultTypeStillRejectsAPojo() {
        assertThatThrownBy(
                        () -> ToolResultUtils.normalizeAgentResult(new Verdict(true, "clean", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be JSON-compatible at result");
    }

    @Test
    void declaringObjectIsTheSameAsDeclaringNothing() {
        Map<String, Object> raw = Map.of("items", List.of(1, 2));

        assertThat(ToolResultUtils.normalizeAgentResult(raw, Object.class))
                .isEqualTo(ToolResultUtils.normalizeAgentResult(raw));
        assertThat(ToolResultUtils.normalizeAgentResult(raw, null))
                .isEqualTo(ToolResultUtils.normalizeAgentResult(raw));
    }

    /** A declared type can still render a field JSON cannot express, so the check stays. */
    @Test
    void aDeclaredResultTypeStillRejectsWhatJsonCannotExpress() {
        Map<String, Object> raw =
                Map.of("approved", true, "note", "clean", "score", Double.POSITIVE_INFINITY);

        assertThatThrownBy(() -> ToolResultUtils.normalizeAgentResult(raw, Verdict.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Non-finite number in sub-agent result at result.score");
    }

    @Test
    void chatMessageContentSerializesContainersAndStringifiesScalars() throws Exception {
        assertThat(ToolResultUtils.toChatMessageContent(null)).isEqualTo("null");
        assertThat(ToolResultUtils.toChatMessageContent("done")).isEqualTo("done");
        assertThat(ToolResultUtils.toChatMessageContent(7)).isEqualTo("7");
        assertThat(ToolResultUtils.toChatMessageContent(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(ToolResultUtils.toChatMessageContent(List.of(1, 2))).isEqualTo("[1,2]");
        assertThat(ToolResultUtils.toChatMessageContent(new int[] {1, 2})).isEqualTo("[1,2]");
        assertThat(ToolResultUtils.toChatMessageContent(MAPPER.createObjectNode().put("a", 1)))
                .isEqualTo("{\"a\":1}");
    }
}
