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

import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOMBSTONE_ENABLED;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link KafkaActionStateCleanupCoordinator}. */
class KafkaActionStateCleanupCoordinatorTest {

    @Test
    void testCommitsBeforeDeleteAndMarksAppliedAfterVerification() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);

        assertThat(coordinator.apply(plan))
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);

        assertThat(transport.events)
                .containsExactly("append:COMMITTED", "delete:{0=10, 1=20}", "append:APPLIED");
        assertThat(transport.deletedOffsets).containsExactlyInAnyOrderEntriesOf(plan.getOffsets());
        assertThat(coordinator.getCommittedBoundary("action-state", "topic-id", Set.of(0, 1)))
                .isEqualTo(plan.getOffsets());
    }

    @Test
    void testDeleteFailureLeavesCommittedPlanForRetry() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failNextDelete = true;
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);

        assertThatThrownBy(() -> coordinator.apply(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated delete failure");
        assertThat(transport.operations.get(plan.getPlanId()).getStatus())
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.COMMITTED);

        assertThat(coordinator.apply(plan))
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
        assertThat(transport.events)
                .containsExactly(
                        "append:COMMITTED",
                        "delete:{0=10, 1=20}",
                        "delete:{0=10, 1=20}",
                        "append:APPLIED");
    }

    @Test
    void testAppliedRecordFailureRetriesDeletionFromCommittedPlan() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failNextAppliedAppend = true;
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);

        assertThatThrownBy(() -> coordinator.apply(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated applied append failure");
        assertThat(transport.operations.get(plan.getPlanId()).getStatus())
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.COMMITTED);

        assertThat(coordinator.apply(plan))
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
        assertThat(transport.events)
                .containsExactly(
                        "append:COMMITTED",
                        "delete:{0=10, 1=20}",
                        "delete:{0=10, 1=20}",
                        "append:APPLIED");
    }

    @Test
    void testCommittedBoundaryRejectsOldRestoreBeforeDeleteSucceeds() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failNextDelete = true;
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);

        assertThatThrownBy(() -> coordinator.apply(plan)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(
                        () ->
                                coordinator.validateRecoveryOffsets(
                                        "action-state", "topic-id", Map.of(0, 9L, 1, 20L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("committed cleanup boundary is 10");
        coordinator.validateRecoveryOffsets("action-state", "topic-id", Map.of(0, 10L, 1, 21L));
    }

    @Test
    void testRecoveryFailsClosedWhenControlHistoryIsEmpty() {
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(new FakeTransport(), true);

        assertThatThrownBy(
                        () ->
                                coordinator.getCommittedBoundary(
                                        "action-state", "topic-id", Set.of(0, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contains no committed boundary");
    }

    @Test
    void testRejectsBackwardBoundary() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        coordinator.apply(plan("checkpoint-42", 10L, 20L));

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-41", 9L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not advance");
    }

    @Test
    void testRejectsBoundaryBelowAvailableBeginningBeforeCommit() {
        FakeTransport transport = new FakeTransport();
        transport.beginningOffsets.put(0, 11L);
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available range is [11");
        assertThat(transport.operations).isEmpty();
        assertThat(transport.events).isEmpty();
    }

    @Test
    void testRejectsBoundaryAboveAvailableEndBeforeCommit() {
        FakeTransport transport = new FakeTransport();
        transport.endOffsets.put(1, 19L);
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available range is [0, 19]");
        assertThat(transport.operations).isEmpty();
        assertThat(transport.events).isEmpty();
    }

    @Test
    void testCommittedPlanRetriesAfterKafkaBeginningPassesBoundary() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);
        transport.operations.put(
                plan.getPlanId(), KafkaActionStateCleanupCoordinator.Operation.committed(plan));
        transport.beginningOffsets.put(0, 11L);
        transport.beginningOffsets.put(1, 21L);
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThat(coordinator.apply(plan))
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
        assertThat(transport.events).containsExactly("delete:{0=10, 1=20}", "append:APPLIED");
    }

    @Test
    void testRejectsIncomparableCommittedBoundariesBeforeDeletion() {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupPlan left = plan("checkpoint-left", 10L, 30L);
        KafkaActionStateCleanupPlan right = plan("checkpoint-right", 20L, 20L);
        transport.operations.put(
                left.getPlanId(), KafkaActionStateCleanupCoordinator.Operation.committed(left));
        transport.operations.put(
                right.getPlanId(), KafkaActionStateCleanupCoordinator.Operation.committed(right));
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(right))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomparable boundaries");
        assertThat(transport.events).isEmpty();
    }

    @Test
    void testAppliedPlanIsIdempotent() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);
        coordinator.apply(plan);
        transport.events.clear();

        assertThat(coordinator.apply(plan))
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
        assertThat(transport.events).isEmpty();
    }

    @Test
    void testCloseClosesTransport() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        coordinator.close();

        assertThat(transport.closed).isTrue();
    }

    @Test
    void testLaterPlanAdvancesEveryPartition() throws Exception {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);
        coordinator.apply(plan("checkpoint-41", 10L, 20L));
        transport.events.clear();

        KafkaActionStateCleanupPlan later = plan("checkpoint-42", 15L, 25L);
        coordinator.apply(later);

        assertThat(transport.events)
                .containsExactly("append:COMMITTED", "delete:{0=15, 1=25}", "append:APPLIED");
        assertThat(coordinator.getCommittedBoundary("action-state", "topic-id", Set.of(0, 1)))
                .isEqualTo(later.getOffsets());
    }

    @Test
    void testControlRecordJsonRoundTrip() throws Exception {
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);
        KafkaActionStateCleanupCoordinator.Operation operation =
                KafkaActionStateCleanupCoordinator.Operation.committed(plan);

        KafkaActionStateCleanupCoordinator.Operation restored =
                KafkaActionStateCleanupCoordinator.Operation.fromJson(operation.toJson());

        assertThat(restored.getPlan()).isEqualTo(plan);
        assertThat(restored.getStatus())
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.COMMITTED);
    }

    @Test
    void testRejectsControlTopicTombstoneWithoutRollingBackBoundary() {
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);
        Map<String, KafkaActionStateCleanupCoordinator.Operation> operations =
                new LinkedHashMap<>();
        operations.put(
                plan.getPlanId(), KafkaActionStateCleanupCoordinator.Operation.committed(plan));
        ConsumerRecord<String, String> tombstone =
                new ConsumerRecord<>("action-state-control", 0, 1L, plan.getPlanId(), null);

        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupCoordinator.applyControlRecord(
                                        operations, tombstone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cleanup boundaries cannot be deleted");
        assertThat(operations).containsKey(plan.getPlanId());
    }

    @Test
    void testRejectsAppliedStatusRegressionWithoutChangingBoundary() throws Exception {
        KafkaActionStateCleanupPlan plan = plan("checkpoint-42", 10L, 20L);
        KafkaActionStateCleanupCoordinator.Operation applied =
                KafkaActionStateCleanupCoordinator.Operation.committed(plan)
                        .withStatus(KafkaActionStateCleanupCoordinator.Status.APPLIED);
        Map<String, KafkaActionStateCleanupCoordinator.Operation> operations =
                new LinkedHashMap<>();
        operations.put(plan.getPlanId(), applied);
        ConsumerRecord<String, String> regression =
                new ConsumerRecord<>(
                        "action-state-control",
                        0,
                        1L,
                        plan.getPlanId(),
                        KafkaActionStateCleanupCoordinator.Operation.committed(plan).toJson());

        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupCoordinator.applyControlRecord(
                                        operations, regression))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regressed applied plan");
        assertThat(operations.get(plan.getPlanId()).getStatus())
                .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
    }

    @Test
    void testRejectsFractionalControlRecordSchema() throws Exception {
        KafkaActionStateCleanupCoordinator.Operation operation =
                KafkaActionStateCleanupCoordinator.Operation.committed(
                        plan("checkpoint-42", 10L, 20L));
        String malformed =
                operation.toJson().replace("\"schemaVersion\":1", "\"schemaVersion\":1.5");

        assertThatThrownBy(() -> KafkaActionStateCleanupCoordinator.Operation.fromJson(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported cleanup control record schema");
    }

    @Test
    void testRejectsRecreatedDataTopicBeforeCommit() {
        FakeTransport transport = new FakeTransport();
        transport.metadata =
                new KafkaActionStateCleanupCoordinator.TopicMetadata(
                        "recreated-topic-id", Set.of(0, 1));
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expects topic-id");
        assertThat(transport.operations).isEmpty();
    }

    @Test
    void testRevalidatesDataTopicImmediatelyBeforeDelete() {
        FakeTransport transport = new FakeTransport();
        transport.metadataByDescribeCall.put(
                2,
                new KafkaActionStateCleanupCoordinator.TopicMetadata(
                        "recreated-topic-id", Set.of(0, 1)));
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expects topic-id");
        assertThat(transport.events).containsExactly("append:COMMITTED");
    }

    @Test
    void testRevalidatesDataTopicAfterDeleteBeforeMarkingApplied() {
        FakeTransport transport = new FakeTransport();
        transport.metadataByDescribeCall.put(
                3,
                new KafkaActionStateCleanupCoordinator.TopicMetadata(
                        "recreated-topic-id", Set.of(0, 1)));
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport);

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expects topic-id");
        assertThat(transport.events).containsExactly("append:COMMITTED", "delete:{0=10, 1=20}");
        assertThat(transport.operations.values())
                .allMatch(
                        operation ->
                                operation.getStatus()
                                        == KafkaActionStateCleanupCoordinator.Status.COMMITTED);
    }

    @Test
    void testRejectsPlanForDifferentConfiguredDataTopicBeforeCommit() {
        FakeTransport transport = new FakeTransport();
        KafkaActionStateCleanupCoordinator coordinator =
                new KafkaActionStateCleanupCoordinator(transport, false, "other-action-state");

        assertThatThrownBy(() -> coordinator.apply(plan("checkpoint-42", 10L, 20L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured for data topic other-action-state");
        assertThat(transport.operations).isEmpty();
    }

    @Test
    void testRejectsTombstonesWithCheckpointAlignedCleanupBeforeConnecting() {
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.set(KAFKA_ACTION_STATE_TOPIC, "action-state");
        configuration.set(KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, "action-state-control");
        configuration.set(KAFKA_ACTION_STATE_TOMBSTONE_ENABLED, true);

        assertThatThrownBy(() -> KafkaActionStateCleanupCoordinator.create(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstones cannot be enabled");
    }

    @Test
    void testRejectsSharedDataAndControlTopicBeforeConnecting() {
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.set(KAFKA_ACTION_STATE_TOPIC, "action-state");
        configuration.set(KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, "action-state");

        assertThatThrownBy(() -> KafkaActionStateCleanupCoordinator.create(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from the data topic");
    }

    @Test
    void testControlTopicRequiresCompactionWithoutDeleteRetention() {
        assertThatCode(
                        () ->
                                KafkaActionStateCleanupCoordinator
                                        .validateControlTopicCleanupPolicy(
                                                "action-state-control", "compact"))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupCoordinator
                                        .validateControlTopicCleanupPolicy(
                                                "action-state-control", "compact,delete"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cleanup.policy=compact without delete retention");
    }

    private static KafkaActionStateCleanupPlan plan(
            String recoveryPoint, long partitionZero, long partitionOne) {
        return KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                recoveryPoint,
                List.of(
                        new KafkaActionStateRecoveryMarker(
                                "action-state",
                                "topic-id",
                                Map.of(0, partitionZero, 1, partitionOne))));
    }

    private static final class FakeTransport
            implements KafkaActionStateCleanupCoordinator.Transport {
        private KafkaActionStateCleanupCoordinator.TopicMetadata metadata =
                new KafkaActionStateCleanupCoordinator.TopicMetadata("topic-id", Set.of(0, 1));
        private final Map<Integer, KafkaActionStateCleanupCoordinator.TopicMetadata>
                metadataByDescribeCall = new HashMap<>();
        private final Map<String, KafkaActionStateCleanupCoordinator.Operation> operations =
                new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();
        private final Map<Integer, Long> deletedOffsets = new HashMap<>();
        private final Map<Integer, Long> beginningOffsets = new HashMap<>(Map.of(0, 0L, 1, 0L));
        private final Map<Integer, Long> endOffsets =
                new HashMap<>(Map.of(0, Long.MAX_VALUE, 1, Long.MAX_VALUE));
        private boolean failNextDelete;
        private boolean failNextAppliedAppend;
        private boolean closed;
        private int describeCalls;

        @Override
        public KafkaActionStateCleanupCoordinator.TopicMetadata describeTopic(String topic) {
            describeCalls++;
            return metadataByDescribeCall.getOrDefault(describeCalls, metadata);
        }

        @Override
        public Map<String, KafkaActionStateCleanupCoordinator.Operation> readOperations() {
            return new LinkedHashMap<>(operations);
        }

        @Override
        public void append(KafkaActionStateCleanupCoordinator.Operation operation) {
            if (operation.getStatus() == KafkaActionStateCleanupCoordinator.Status.APPLIED
                    && failNextAppliedAppend) {
                failNextAppliedAppend = false;
                throw new IllegalStateException("simulated applied append failure");
            }
            events.add("append:" + operation.getStatus());
            operations.put(operation.getPlan().getPlanId(), operation);
        }

        @Override
        public void validateOffsetsAvailable(
                String topic, String topicId, Map<Integer, Long> offsets) {
            offsets.forEach(
                    (partition, requestedOffset) -> {
                        long beginningOffset = beginningOffsets.get(partition);
                        long endOffset = endOffsets.get(partition);
                        if (requestedOffset < beginningOffset || requestedOffset > endOffset) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Cannot commit Kafka cleanup boundary for %s-%s at offset %s because the available range is [%s, %s]",
                                            topic,
                                            partition,
                                            requestedOffset,
                                            beginningOffset,
                                            endOffset));
                        }
                    });
        }

        @Override
        public void deleteBefore(String topic, String topicId, Map<Integer, Long> offsets) {
            events.add("delete:" + new java.util.TreeMap<>(offsets));
            if (failNextDelete) {
                failNextDelete = false;
                throw new IllegalStateException("simulated delete failure");
            }
            deletedOffsets.putAll(offsets);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
