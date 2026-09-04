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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.annotation.Internal;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.Preconditions;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOMBSTONE_ENABLED;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;

/**
 * Commits and applies explicit Kafka action-state prefix cleanup plans.
 *
 * <p>The control topic is the durable source of truth. A {@link Status#COMMITTED} record is written
 * before any data is deleted, making that boundary the logical point of no return. Reapplying the
 * same plan is idempotent: an unfinished committed operation retries deletion and advances to
 * {@link Status#APPLIED} only after Kafka reports beginning offsets at or beyond the boundary.
 */
@Internal
public final class KafkaActionStateCleanupCoordinator implements AutoCloseable {

    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);

    /** Durable lifecycle of one immutable cleanup plan. */
    public enum Status {
        COMMITTED,
        APPLIED
    }

    private final Transport transport;
    private final boolean committedBoundaryRequired;
    private final String expectedDataTopic;

    /**
     * Creates an administrative coordinator using the Kafka settings in the agent configuration.
     * The control topic is created when absent, and the returned coordinator can commit and apply
     * cleanup plans.
     */
    public static KafkaActionStateCleanupCoordinator create(AgentConfiguration configuration) {
        return create(configuration, true, false);
    }

    /** Creates a read-only-boundary coordinator for action-state recovery. */
    static KafkaActionStateCleanupCoordinator createForRecovery(AgentConfiguration configuration) {
        return create(configuration, false, true);
    }

    private static KafkaActionStateCleanupCoordinator create(
            AgentConfiguration configuration,
            boolean createControlTopic,
            boolean committedBoundaryRequired) {
        Preconditions.checkNotNull(configuration, "Agent configuration must not be null");
        String dataTopic =
                requireText(
                        configuration.get(KAFKA_ACTION_STATE_TOPIC), "Kafka action-state topic");
        String controlTopic =
                requireText(
                        configuration.get(KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC),
                        "Kafka action-state cleanup control topic");
        Preconditions.checkArgument(
                !dataTopic.equals(controlTopic),
                "Kafka action-state cleanup control topic must differ from the data topic");
        Preconditions.checkArgument(
                !configuration.get(KAFKA_ACTION_STATE_TOMBSTONE_ENABLED),
                "Per-key Kafka tombstones cannot be enabled with checkpoint-aligned cleanup");
        return new KafkaActionStateCleanupCoordinator(
                new KafkaTransport(
                        configuration.get(KAFKA_BOOTSTRAP_SERVERS),
                        controlTopic,
                        configuration.get(KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR),
                        createControlTopic),
                committedBoundaryRequired,
                dataTopic);
    }

    KafkaActionStateCleanupCoordinator(Transport transport) {
        this(transport, false, null);
    }

    KafkaActionStateCleanupCoordinator(Transport transport, boolean committedBoundaryRequired) {
        this(transport, committedBoundaryRequired, null);
    }

    KafkaActionStateCleanupCoordinator(
            Transport transport, boolean committedBoundaryRequired, String expectedDataTopic) {
        this.transport =
                Preconditions.checkNotNull(transport, "Cleanup transport must not be null");
        this.committedBoundaryRequired = committedBoundaryRequired;
        this.expectedDataTopic = expectedDataTopic;
    }

    /**
     * Commits and physically applies a reviewed plan. If the plan was already committed, this
     * resumes it without requiring another logical boundary change.
     */
    public synchronized Status apply(KafkaActionStateCleanupPlan plan) throws Exception {
        Preconditions.checkNotNull(plan, "Cleanup plan must not be null");
        validateExpectedDataTopic(plan.getTopic());
        TopicMetadata topicMetadata = transport.describeTopic(plan.getTopic());
        validatePlanTopic(plan, topicMetadata);

        Map<String, Operation> operations = transport.readOperations();
        validateOperationSet(operations.values(), plan);
        Operation existing = operations.get(plan.getPlanId());
        if (existing != null) {
            Preconditions.checkState(
                    existing.getPlan().equals(plan),
                    "Cleanup control record %s does not match the supplied plan",
                    plan.getPlanId());
            if (existing.getStatus() == Status.APPLIED) {
                return Status.APPLIED;
            }
        } else {
            Map<Integer, Long> currentBoundary = effectiveBoundary(operations.values());
            Preconditions.checkArgument(
                    plan.advancesOrEquals(currentBoundary),
                    "Cleanup plan %s does not advance the committed boundary %s",
                    plan.getPlanId(),
                    currentBoundary);
            transport.validateOffsetsAvailable(
                    plan.getTopic(), plan.getTopicId(), plan.getOffsets());
            transport.append(Operation.committed(plan));
        }

        operations = transport.readOperations();
        validateOperationSet(operations.values(), plan);
        Map<Integer, Long> targetBoundary = effectiveBoundary(operations.values());
        validatePlanTopic(plan, transport.describeTopic(plan.getTopic()));
        transport.deleteBefore(plan.getTopic(), plan.getTopicId(), targetBoundary);
        validatePlanTopic(plan, transport.describeTopic(plan.getTopic()));

        for (Operation operation : operations.values()) {
            if (operation.getStatus() == Status.COMMITTED) {
                transport.append(operation.withStatus(Status.APPLIED));
            }
        }
        return Status.APPLIED;
    }

    /** Returns the effective logical boundary committed for the supplied physical topic. */
    public synchronized Map<Integer, Long> getCommittedBoundary(
            String topic, String topicId, Set<Integer> partitions) throws Exception {
        validateExpectedDataTopic(topic);
        List<Operation> operations = new ArrayList<>(transport.readOperations().values());
        if (operations.isEmpty()) {
            Preconditions.checkState(
                    !committedBoundaryRequired,
                    "Kafka cleanup control topic contains no committed boundary; apply a cleanup plan before enabling checkpoint-aligned cleanup during recovery");
            return Collections.emptyMap();
        }
        for (Operation operation : operations) {
            KafkaActionStateCleanupPlan plan = operation.getPlan();
            Preconditions.checkState(
                    topic.equals(plan.getTopic()) && topicId.equals(plan.getTopicId()),
                    "Cleanup control topic contains a boundary for a different Kafka topic");
            Preconditions.checkState(
                    partitions.equals(plan.getOffsets().keySet()),
                    "Cleanup control topic contains a different Kafka partition set");
        }
        validateComparable(operations);
        return effectiveBoundary(operations);
    }

    /** Rejects a restore point older than the durable logical cleanup boundary. */
    public synchronized void validateRecoveryOffsets(
            String topic, String topicId, Map<Integer, Long> recoveryOffsets) throws Exception {
        Map<Integer, Long> boundary =
                getCommittedBoundary(topic, topicId, recoveryOffsets.keySet());
        boundary.forEach(
                (partition, committedOffset) -> {
                    long requestedOffset = recoveryOffsets.get(partition);
                    Preconditions.checkState(
                            requestedOffset >= committedOffset,
                            "Cannot restore Kafka action state for %s-%s from offset %s because the committed cleanup boundary is %s",
                            topic,
                            partition,
                            requestedOffset,
                            committedOffset);
                });
    }

    private static void validatePlanTopic(
            KafkaActionStateCleanupPlan plan, TopicMetadata metadata) {
        Preconditions.checkArgument(
                plan.getTopicId().equals(metadata.getTopicId()),
                "Kafka action-state topic %s has ID %s, but cleanup plan %s expects %s",
                plan.getTopic(),
                metadata.getTopicId(),
                plan.getPlanId(),
                plan.getTopicId());
        Preconditions.checkArgument(
                plan.getOffsets().keySet().equals(metadata.getPartitions()),
                "Kafka action-state topic %s has partitions %s, but cleanup plan %s expects %s",
                plan.getTopic(),
                metadata.getPartitions(),
                plan.getPlanId(),
                plan.getOffsets().keySet());
    }

    private static void validateOperationSet(
            Iterable<Operation> operations, KafkaActionStateCleanupPlan expectedPlan) {
        for (Operation operation : operations) {
            KafkaActionStateCleanupPlan plan = operation.getPlan();
            Preconditions.checkState(
                    expectedPlan.getTopic().equals(plan.getTopic())
                            && expectedPlan.getTopicId().equals(plan.getTopicId()),
                    "Cleanup control topic is not dedicated to one Kafka action-state history");
            Preconditions.checkState(
                    expectedPlan.getOffsets().keySet().equals(plan.getOffsets().keySet()),
                    "Cleanup control topic contains incompatible Kafka partition sets");
        }
        validateComparable(operations);
    }

    private static void validateComparable(Iterable<Operation> operations) {
        List<KafkaActionStateCleanupPlan> plans = new ArrayList<>();
        operations.forEach(operation -> plans.add(operation.getPlan()));
        for (int left = 0; left < plans.size(); left++) {
            for (int right = left + 1; right < plans.size(); right++) {
                KafkaActionStateCleanupPlan leftPlan = plans.get(left);
                KafkaActionStateCleanupPlan rightPlan = plans.get(right);
                Preconditions.checkState(
                        leftPlan.advancesOrEquals(rightPlan.getOffsets())
                                || rightPlan.advancesOrEquals(leftPlan.getOffsets()),
                        "Cleanup control topic contains incomparable boundaries %s and %s",
                        leftPlan.getPlanId(),
                        rightPlan.getPlanId());
            }
        }
    }

    private static Map<Integer, Long> effectiveBoundary(Iterable<Operation> operations) {
        Map<Integer, Long> boundary = new HashMap<>();
        for (Operation operation : operations) {
            operation
                    .getPlan()
                    .getOffsets()
                    .forEach((partition, offset) -> boundary.merge(partition, offset, Math::max));
        }
        return Collections.unmodifiableMap(new TreeMap<>(boundary));
    }

    private static String requireText(String value, String name) {
        Preconditions.checkArgument(
                value != null && !value.trim().isEmpty(), "%s must not be blank", name);
        return value;
    }

    private void validateExpectedDataTopic(String topic) {
        Preconditions.checkArgument(
                expectedDataTopic == null || expectedDataTopic.equals(topic),
                "Kafka cleanup coordinator is configured for data topic %s, but received %s",
                expectedDataTopic,
                topic);
    }

    static void validateControlTopicCleanupPolicy(String topic, String cleanupPolicy) {
        Preconditions.checkState(
                "compact".equals(cleanupPolicy),
                "Kafka cleanup control topic %s must use cleanup.policy=compact without delete retention",
                topic);
    }

    @Override
    public void close() throws Exception {
        transport.close();
    }

    interface Transport extends AutoCloseable {
        TopicMetadata describeTopic(String topic) throws Exception;

        Map<String, Operation> readOperations() throws Exception;

        void append(Operation operation) throws Exception;

        void validateOffsetsAvailable(String topic, String topicId, Map<Integer, Long> offsets)
                throws Exception;

        void deleteBefore(String topic, String topicId, Map<Integer, Long> offsets)
                throws Exception;
    }

    static final class TopicMetadata {
        private final String topicId;
        private final Set<Integer> partitions;

        TopicMetadata(String topicId, Set<Integer> partitions) {
            this.topicId = requireText(topicId, "Kafka topic ID");
            this.partitions =
                    Collections.unmodifiableSet(
                            new HashSet<>(
                                    Preconditions.checkNotNull(
                                            partitions, "Kafka partitions must not be null")));
        }

        String getTopicId() {
            return topicId;
        }

        Set<Integer> getPartitions() {
            return partitions;
        }
    }

    static final class Operation {
        private static final int CURRENT_SCHEMA_VERSION = 1;
        private static final ObjectMapper MAPPER =
                new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        private static final Set<String> JSON_FIELDS = Set.of("schemaVersion", "status", "plan");

        private final KafkaActionStateCleanupPlan plan;
        private final Status status;

        private Operation(KafkaActionStateCleanupPlan plan, Status status) {
            this.plan = Preconditions.checkNotNull(plan, "Cleanup plan must not be null");
            this.status = Preconditions.checkNotNull(status, "Cleanup status must not be null");
        }

        static Operation committed(KafkaActionStateCleanupPlan plan) {
            return new Operation(plan, Status.COMMITTED);
        }

        Operation withStatus(Status newStatus) {
            Preconditions.checkArgument(
                    status != Status.APPLIED || newStatus == Status.APPLIED,
                    "An applied cleanup operation cannot return to committed");
            return new Operation(plan, newStatus);
        }

        KafkaActionStateCleanupPlan getPlan() {
            return plan;
        }

        Status getStatus() {
            return status;
        }

        String toJson() throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
            root.put("status", status.name());
            root.set("plan", MAPPER.readTree(plan.toJson()));
            return MAPPER.writeValueAsString(root);
        }

        static Operation fromJson(String json) throws Exception {
            JsonNode root = MAPPER.readTree(json);
            Preconditions.checkArgument(
                    root != null && root.isObject(), "Cleanup control record must be JSON");
            Set<String> fieldNames = new HashSet<>();
            root.fieldNames().forEachRemaining(fieldNames::add);
            Preconditions.checkArgument(
                    fieldNames.equals(JSON_FIELDS),
                    "Cleanup control record fields must be exactly %s, but were %s",
                    JSON_FIELDS,
                    fieldNames);
            JsonNode schemaVersion = root.get("schemaVersion");
            Preconditions.checkArgument(
                    schemaVersion.isIntegralNumber()
                            && schemaVersion.canConvertToInt()
                            && schemaVersion.intValue() == CURRENT_SCHEMA_VERSION,
                    "Unsupported cleanup control record schema %s",
                    schemaVersion);
            JsonNode statusNode = root.get("status");
            Preconditions.checkArgument(
                    statusNode.isTextual(), "Cleanup control record status must be a string");
            Status status = Status.valueOf(statusNode.textValue());
            JsonNode planNode = root.get("plan");
            Preconditions.checkArgument(planNode != null, "Cleanup control record has no plan");
            return new Operation(KafkaActionStateCleanupPlan.fromJson(planNode.toString()), status);
        }
    }

    static void applyControlRecord(
            Map<String, Operation> operations, ConsumerRecord<String, String> record)
            throws Exception {
        Preconditions.checkState(
                record.key() != null, "Kafka cleanup control record must have a plan ID key");
        Preconditions.checkState(
                record.value() != null,
                "Kafka cleanup control topic contains a tombstone for plan %s; cleanup boundaries cannot be deleted",
                record.key());
        Operation operation = Operation.fromJson(record.value());
        Preconditions.checkState(
                record.key().equals(operation.getPlan().getPlanId()),
                "Cleanup control record key does not match its plan ID");
        Operation previous = operations.get(record.key());
        Preconditions.checkState(
                previous == null || previous.getPlan().equals(operation.getPlan()),
                "Cleanup control record changed immutable plan %s",
                record.key());
        Preconditions.checkState(
                previous == null
                        || previous.getStatus() != Status.APPLIED
                        || operation.getStatus() == Status.APPLIED,
                "Cleanup control record regressed applied plan %s",
                record.key());
        operations.put(record.key(), operation);
    }

    private static final class KafkaTransport implements Transport {
        private final String controlTopic;
        private final AdminClient adminClient;
        private final Producer<String, String> producer;
        private final Consumer<String, String> consumer;

        private KafkaTransport(
                String bootstrapServers,
                String controlTopic,
                int replicationFactor,
                boolean createControlTopic) {
            this.controlTopic = requireText(controlTopic, "Kafka cleanup control topic");
            Properties common = new Properties();
            common.put(
                    BOOTSTRAP_SERVERS_CONFIG,
                    requireText(bootstrapServers, "Kafka bootstrap servers"));
            this.adminClient = AdminClient.create(common);
            Producer<String, String> createdProducer = null;
            Consumer<String, String> createdConsumer = null;
            try {
                ensureControlTopic(replicationFactor, createControlTopic);

                if (createControlTopic) {
                    Properties producerProperties = new Properties();
                    producerProperties.putAll(common);
                    producerProperties.put(
                            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                    producerProperties.put(
                            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                    producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
                    producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
                    createdProducer = new KafkaProducer<>(producerProperties);
                }

                Properties consumerProperties = new Properties();
                consumerProperties.putAll(common);
                consumerProperties.put(
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                consumerProperties.put(
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                consumerProperties.put(
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "action-state-cleanup-control-" + UUID.randomUUID());
                consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
                consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                consumerProperties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
                createdConsumer = new KafkaConsumer<>(consumerProperties);
            } catch (RuntimeException | Error failure) {
                if (createdProducer != null) {
                    try {
                        createdProducer.close();
                    } catch (Throwable closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (createdConsumer != null) {
                    try {
                        createdConsumer.close();
                    } catch (Throwable closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                try {
                    adminClient.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            this.producer = createdProducer;
            this.consumer = createdConsumer;
        }

        @Override
        public TopicMetadata describeTopic(String topic) throws Exception {
            TopicDescription description =
                    adminClient
                            .describeTopics(List.of(topic))
                            .allTopicNames()
                            .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .get(topic);
            Preconditions.checkState(description != null, "Kafka topic does not exist: %s", topic);
            Set<Integer> partitions = new HashSet<>();
            description.partitions().forEach(value -> partitions.add(value.partition()));
            return new TopicMetadata(description.topicId().toString(), partitions);
        }

        @Override
        public Map<String, Operation> readOperations() throws Exception {
            TopicPartition partition = new TopicPartition(controlTopic, 0);
            consumer.assign(List.of(partition));
            long beginning = consumer.beginningOffsets(List.of(partition)).get(partition);
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            consumer.seek(partition, beginning);

            Map<String, Operation> operations = new LinkedHashMap<>();
            long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
            while (consumer.position(partition) < end) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.offset() >= end) {
                        continue;
                    }
                    applyControlRecord(operations, record);
                }
                if (consumer.position(partition) < end) {
                    Preconditions.checkState(
                            System.nanoTime() < deadline,
                            "Timed out reading Kafka cleanup control topic %s",
                            controlTopic);
                }
            }
            return operations;
        }

        @Override
        public void append(Operation operation) throws Exception {
            Preconditions.checkState(
                    producer != null,
                    "Recovery-only Kafka cleanup coordinator cannot append control records");
            producer.send(
                            new ProducerRecord<>(
                                    controlTopic,
                                    operation.getPlan().getPlanId(),
                                    operation.toJson()))
                    .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void validateOffsetsAvailable(
                String topic, String expectedTopicId, Map<Integer, Long> offsets) throws Exception {
            validateTopicIdentity(topic, expectedTopicId, offsets.keySet());
            Map<TopicPartition, Long> beginningOffsets =
                    listOffsets(topic, offsets.keySet(), OffsetSpec.earliest());
            Map<TopicPartition, Long> endOffsets =
                    listOffsets(topic, offsets.keySet(), OffsetSpec.latest());
            validateTopicIdentity(topic, expectedTopicId, offsets.keySet());
            offsets.forEach(
                    (partition, requestedOffset) -> {
                        TopicPartition topicPartition = new TopicPartition(topic, partition);
                        Long beginningOffset = beginningOffsets.get(topicPartition);
                        Long endOffset = endOffsets.get(topicPartition);
                        Preconditions.checkArgument(
                                beginningOffset != null
                                        && endOffset != null
                                        && requestedOffset >= beginningOffset
                                        && requestedOffset <= endOffset,
                                "Cannot commit Kafka cleanup boundary for %s at offset %s because the available range is [%s, %s]",
                                topicPartition,
                                requestedOffset,
                                beginningOffset,
                                endOffset);
                    });
        }

        @Override
        public void deleteBefore(String topic, String expectedTopicId, Map<Integer, Long> offsets)
                throws Exception {
            validateTopicIdentity(topic, expectedTopicId, offsets.keySet());
            Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
            offsets.forEach(
                    (partition, offset) ->
                            recordsToDelete.put(
                                    new TopicPartition(topic, partition),
                                    RecordsToDelete.beforeOffset(offset)));
            adminClient
                    .deleteRecords(recordsToDelete)
                    .all()
                    .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            Map<TopicPartition, Long> beginningOffsets =
                    listOffsets(topic, offsets.keySet(), OffsetSpec.earliest());
            offsets.forEach(
                    (partition, target) -> {
                        TopicPartition topicPartition = new TopicPartition(topic, partition);
                        Long beginning = beginningOffsets.get(topicPartition);
                        Preconditions.checkState(
                                beginning != null && beginning >= target,
                                "Kafka cleanup for %s requested offset %s but beginning offset is %s",
                                topicPartition,
                                target,
                                beginning);
                    });
            validateTopicIdentity(topic, expectedTopicId, offsets.keySet());
        }

        private Map<TopicPartition, Long> listOffsets(
                String topic, Set<Integer> partitions, OffsetSpec offsetSpec) throws Exception {
            Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
            partitions.forEach(
                    partition -> requests.put(new TopicPartition(topic, partition), offsetSpec));
            Map<TopicPartition, Long> offsets = new HashMap<>();
            adminClient
                    .listOffsets(requests)
                    .all()
                    .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .forEach((partition, info) -> offsets.put(partition, info.offset()));
            return offsets;
        }

        private void validateTopicIdentity(
                String topic, String expectedTopicId, Set<Integer> expectedPartitions)
                throws Exception {
            TopicMetadata metadata = describeTopic(topic);
            Preconditions.checkState(
                    expectedTopicId.equals(metadata.getTopicId()),
                    "Kafka action-state topic %s changed during cleanup; expected ID %s but found %s",
                    topic,
                    expectedTopicId,
                    metadata.getTopicId());
            Preconditions.checkState(
                    expectedPartitions.equals(metadata.getPartitions()),
                    "Kafka action-state topic %s changed partitions during cleanup; expected %s but found %s",
                    topic,
                    expectedPartitions,
                    metadata.getPartitions());
        }

        private void ensureControlTopic(int replicationFactor, boolean createControlTopic) {
            try {
                boolean exists =
                        adminClient
                                .listTopics()
                                .names()
                                .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                .contains(controlTopic);
                if (!exists) {
                    Preconditions.checkState(
                            createControlTopic,
                            "Kafka cleanup control topic %s does not exist; apply a cleanup plan before enabling checkpoint-aligned cleanup during recovery",
                            controlTopic);
                    NewTopic topic = new NewTopic(controlTopic, 1, (short) replicationFactor);
                    topic.configs(Map.of("cleanup.policy", "compact"));
                    try {
                        adminClient
                                .createTopics(List.of(topic))
                                .all()
                                .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (ExecutionException e) {
                        if (!(e.getCause() instanceof TopicExistsException)) {
                            throw e;
                        }
                    }
                }

                TopicMetadata metadata = describeTopic(controlTopic);
                Preconditions.checkState(
                        metadata.getPartitions().equals(Set.of(0)),
                        "Kafka cleanup control topic %s must have exactly one partition",
                        controlTopic);
                ConfigResource resource =
                        new ConfigResource(ConfigResource.Type.TOPIC, controlTopic);
                Config config =
                        adminClient
                                .describeConfigs(List.of(resource))
                                .all()
                                .get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                .get(resource);
                ConfigEntry cleanupPolicy = config.get("cleanup.policy");
                validateControlTopicCleanupPolicy(
                        controlTopic, cleanupPolicy == null ? null : cleanupPolicy.value());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to create or validate Kafka cleanup control topic " + controlTopic,
                        e);
            }
        }

        @Override
        public void close() throws Exception {
            Throwable firstFailure = null;
            if (producer != null) {
                try {
                    producer.close();
                } catch (Throwable failure) {
                    firstFailure = failure;
                }
            }
            try {
                consumer.close();
            } catch (Throwable failure) {
                firstFailure = ExceptionUtils.firstOrSuppressed(failure, firstFailure);
            }
            try {
                adminClient.close();
            } catch (Throwable failure) {
                firstFailure = ExceptionUtils.firstOrSuppressed(failure, firstFailure);
            }
            if (firstFailure != null) {
                ExceptionUtils.rethrowException(firstFailure);
            }
        }
    }
}
