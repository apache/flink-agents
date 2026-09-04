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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.kafka.common.Uuid;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** Tests for {@link KafkaActionStateCleanupPlan}. */
class KafkaActionStateCleanupPlanTest {

    @Test
    void testUsesPerPartitionMinimumAcrossUnionMarkers() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42",
                        List.of(marker(Map.of(0, 15L, 1, 30L)), marker(Map.of(0, 10L, 1, 35L))));

        assertThat(plan.getOffsets()).containsOnly(entry(0, 10L), entry(1, 30L));
        assertThat(plan.getSourceRecoveryPoint()).isEqualTo("checkpoint-42");
        assertThat(plan.getPlanId()).hasSize(64);
    }

    @Test
    void testJsonRoundTripPreservesReviewedPlan() throws Exception {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "s3://checkpoints/savepoint-1", List.of(marker(Map.of(0, 10L, 1, 20L))));

        KafkaActionStateCleanupPlan restored = KafkaActionStateCleanupPlan.fromJson(plan.toJson());

        assertThat(restored).isEqualTo(plan);
    }

    @Test
    void testRejectsTamperedJson() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String tampered = plan.toJson().replace("\"0\" : 10", "\"0\" : 11");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan ID does not match");
    }

    @Test
    void testRejectsNonStringPlanId() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String malformed = plan.toJson().replace('"' + plan.getPlanId() + '"', "123");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cleanup plan planId must be a string");
    }

    @Test
    void testRejectsUnknownPlanField() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String malformed = plan.toJson().replaceFirst("\\{", "{ \"unexpected\" : true,");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields must be exactly");
    }

    @Test
    void testRejectsDuplicatePlanField() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String malformed = plan.toJson().replaceFirst("\\{", "{ \"schemaVersion\" : 1,");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(malformed))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate field 'schemaVersion'");
    }

    @Test
    void testRejectsNonCanonicalPartitionKeyAlias() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String malformed = plan.toJson().replace("\"0\" : 10", "\"+0\" : 999,\n    \"0\" : 10");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition +0 must use canonical decimal form");
    }

    @Test
    void testRejectsFractionalOffsets() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42", List.of(marker(Map.of(0, 10L, 1, 20L))));
        String fractional = plan.toJson().replace("\"0\" : 10", "\"0\" : 10.5");

        assertThatThrownBy(() -> KafkaActionStateCleanupPlan.fromJson(fractional))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
    }

    @Test
    void testRejectsLegacyMarkersForDestructiveCleanup() {
        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                                        "checkpoint-42", List.of(Map.of(0, 10L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires versioned Kafka recovery markers");
    }

    @Test
    void testRejectsUnavailableTopicIdentity() {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        "action-state", Uuid.ZERO_UUID.toString(), Map.of(0, 10L));

        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                                        "checkpoint-42", List.of(marker)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broker-provided Kafka topic ID");
    }

    @Test
    void testRejectsMarkersFromDifferentPhysicalTopics() {
        KafkaActionStateRecoveryMarker otherTopic =
                new KafkaActionStateRecoveryMarker(
                        "action-state", "other-topic-id", Map.of(0, 10L, 1, 20L));

        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                                        "checkpoint-42",
                                        List.of(marker(Map.of(0, 10L, 1, 20L)), otherTopic)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one physical Kafka topic");
    }

    @Test
    void testRejectsMarkersWithDifferentPartitionSets() {
        KafkaActionStateRecoveryMarker missingPartition =
                new KafkaActionStateRecoveryMarker("action-state", "topic-id", Map.of(0, 10L));

        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                                        "checkpoint-42",
                                        List.of(marker(Map.of(0, 10L, 1, 20L)), missingPartition)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different Kafka partition sets");
    }

    private static KafkaActionStateRecoveryMarker marker(Map<Integer, Long> offsets) {
        return new KafkaActionStateRecoveryMarker("action-state", "topic-id", offsets);
    }
}
