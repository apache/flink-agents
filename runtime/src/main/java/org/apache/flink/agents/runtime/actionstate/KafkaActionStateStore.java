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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.Preconditions;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOMBSTONE_ENABLED;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;
import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.PARTITIONER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

/**
 * An implementation of ActionStateStore that uses Kafka as the backend storage for action states.
 * This class provides methods to put, get, and retrieve all action states associated with a given
 * key and action.
 */
public class KafkaActionStateStore implements ActionStateStore {

    private static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofMillis(1000);
    private static final Duration REBUILD_PROGRESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(KafkaActionStateStore.class);
    private static final Long DEFAULT_FUTURE_GET_TIMEOUT_MS = 30_000L;

    private final AgentConfiguration agentConfiguration;

    // In memory action state for quick state retrieval
    private final Map<String, ActionState> actionStates;

    // Record the lastest sequence number for each key that should be considered as valid
    private final Map<String, Long> latestKeySeqNum;

    // Kafka producer
    private final Producer<String, ActionState> producer;
    // Kafka consumer
    private final Consumer<String, ActionState> consumer;

    // Kafka topic that stores action states
    private final String topic;

    // Whether pruning sends tombstone records for log compaction
    private final boolean tombstoneEnabled;

    // When set, only records whose key-group is accepted by this predicate are kept in the
    // in-memory cache during rebuildState; null means retain all keys (default).
    private IntPredicate ownershipFilter;

    // The operator's maximum parallelism, used to compute key-groups consistently with Flink.
    private final int maxParallelism;

    // Kafka topic identity and partition set captured when this store instance starts
    private final KafkaTopicMetadata topicMetadata;
    private final TopicMetadataLoader topicMetadataLoader;

    // Present only when checkpoint-aligned cleanup boundary enforcement is configured.
    private final KafkaActionStateCleanupCoordinator cleanupCoordinator;

    @VisibleForTesting
    KafkaActionStateStore(
            Map<String, ActionState> actionStates,
            AgentConfiguration agentConfiguration,
            Producer<String, ActionState> producer,
            Consumer<String, ActionState> consumer,
            String topic,
            int maxParallelism) {
        this(actionStates, agentConfiguration, producer, consumer, topic, maxParallelism, null);
    }

    @VisibleForTesting
    KafkaActionStateStore(
            Map<String, ActionState> actionStates,
            AgentConfiguration agentConfiguration,
            Producer<String, ActionState> producer,
            Consumer<String, ActionState> consumer,
            String topic,
            int maxParallelism,
            KafkaActionStateCleanupCoordinator cleanupCoordinator) {
        this.actionStates = actionStates;
        this.producer = producer;
        this.consumer = consumer;
        this.topic = topic;
        this.topicMetadataLoader = () -> testTopicMetadata(consumer, topic);
        this.topicMetadata = testTopicMetadata(consumer, topic);
        this.latestKeySeqNum = new HashMap<>();
        this.agentConfiguration = agentConfiguration;
        this.tombstoneEnabled = agentConfiguration.get(KAFKA_ACTION_STATE_TOMBSTONE_ENABLED);
        this.maxParallelism = maxParallelism;
        Preconditions.checkArgument(
                cleanupCoordinator == null || !this.tombstoneEnabled,
                "Per-key Kafka tombstones cannot be enabled with checkpoint-aligned cleanup");
        this.cleanupCoordinator = cleanupCoordinator;
    }

    /** Constructs a new KafkaActionStateStore with custom Kafka configuration. */
    public KafkaActionStateStore(AgentConfiguration agentConfiguration, int maxParallelism) {
        Preconditions.checkArgument(
                maxParallelism > 0,
                "maxParallelism must be positive but was %s; it must be set to the operator's max"
                        + " parallelism so key-groups match Flink's key-group assignment.",
                maxParallelism);
        this.maxParallelism = maxParallelism;
        this.actionStates = new HashMap<>();
        this.latestKeySeqNum = new HashMap<>();
        this.agentConfiguration = agentConfiguration;
        this.tombstoneEnabled = agentConfiguration.get(KAFKA_ACTION_STATE_TOMBSTONE_ENABLED);
        String cleanupControlTopic =
                agentConfiguration.get(KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC);
        Preconditions.checkArgument(
                cleanupControlTopic == null || !cleanupControlTopic.trim().isEmpty(),
                "Kafka action-state cleanup control topic must not be blank");
        Preconditions.checkArgument(
                cleanupControlTopic == null || !this.tombstoneEnabled,
                "Per-key Kafka tombstones cannot be enabled with checkpoint-aligned cleanup");
        this.topic =
                Preconditions.checkNotNull(
                        agentConfiguration.get(KAFKA_ACTION_STATE_TOPIC),
                        "Kafka action state topic must be configured");
        // create the topic if not exists
        maybeCreateTopic();
        this.topicMetadataLoader = this::loadTopicMetadata;
        try {
            this.topicMetadata = topicMetadataLoader.load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Kafka topic metadata for " + topic, e);
        }
        KafkaActionStateCleanupCoordinator createdCoordinator = null;
        Producer<String, ActionState> createdProducer = null;
        Consumer<String, ActionState> createdConsumer = null;
        try {
            createdCoordinator =
                    cleanupControlTopic == null
                            ? null
                            : KafkaActionStateCleanupCoordinator.createForRecovery(
                                    agentConfiguration);
            createdProducer = new KafkaProducer<>(createProducerProp());
            createdConsumer = new KafkaConsumer<>(createConsumerProp());
        } catch (RuntimeException | Error failure) {
            closeAfterInitializationFailure(createdConsumer, failure);
            closeAfterInitializationFailure(createdProducer, failure);
            closeAfterInitializationFailure(createdCoordinator, failure);
            throw failure;
        }
        this.cleanupCoordinator = createdCoordinator;
        this.producer = createdProducer;
        this.consumer = createdConsumer;
        LOG.info("Initialized KafkaActionStateStore with topic: {}", topic);
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws Exception {
        if (producer == null) {
            LOG.error("Producer is null, cannot put action state to Kafka");
            return;
        }

        String stateKey = generateKey(key, seqNum, action, event, maxParallelism);
        try {
            ProducerRecord<String, ActionState> kafkaRecord =
                    new ProducerRecord<>(topic, stateKey, state);
            producer.send(kafkaRecord);
            actionStates.put(stateKey, state);
            producer.flush();
            LOG.debug(
                    "Stored action state to Kafka: key={}, isCompleted={}",
                    stateKey,
                    state.isCompleted());
        } catch (Exception e) {
            throw new RuntimeException("Failed to send action state to Kafka", e);
        }
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws Exception {
        String stateKey = generateKey(key, seqNum, action, event, maxParallelism);

        LOG.debug(
                "Looking up action state: key={}, seqNum={}, stateKey={}, cachedStates={}",
                key,
                seqNum,
                stateKey,
                actionStates.keySet());

        boolean hasDivergence = checkDivergence(key, seqNum);

        if (!actionStates.containsKey(stateKey) || hasDivergence) {
            // Clean up this key's states with sequence number greater than the requested seqNum.
            actionStates
                    .keySet()
                    .removeIf(
                            cachedKey ->
                                    ActionStateUtil.matchesBusinessKeyWithSeqNum(
                                            cachedKey, key, stateSeqNum -> stateSeqNum > seqNum));
        }

        ActionState result = actionStates.get(stateKey);
        if (result != null) {
            LOG.debug("Found action state: key={}, isCompleted={}", stateKey, result.isCompleted());
        } else {
            LOG.debug("Action state not found: key={}", stateKey);
        }

        return result;
    }

    private boolean checkDivergence(Object key, long seqNum) {
        return actionStates.keySet().stream()
                        .filter(k -> ActionStateUtil.matchesBusinessKeyAndSeqNum(k, key, seqNum))
                        .count()
                > 1;
    }

    @Override
    public void rebuildState(List<Object> recoveryMarkers) {
        Preconditions.checkNotNull(recoveryMarkers, "Recovery markers must not be null");
        LOG.info("Rebuilding state from {} recovery markers", recoveryMarkers.size());
        try {
            KafkaTopicMetadata currentTopicMetadata = loadVerifiedTopicMetadata();
            if (recoveryMarkers.isEmpty()) {
                if (cleanupCoordinator != null) {
                    Map<Integer, Long> boundary =
                            cleanupCoordinator.getCommittedBoundary(
                                    topic,
                                    currentTopicMetadata.getTopicId(),
                                    currentTopicMetadata.getPartitions());
                    Preconditions.checkState(
                            boundary.isEmpty(),
                            "Cannot initialize Kafka action state without a recovery marker because cleanup boundary %s is committed",
                            boundary);
                }
                LOG.info("No recovery markers, skipping state rebuild");
                return;
            }

            Map<Integer, Long> partitionMap =
                    mergeAndValidateRecoveryMarkers(recoveryMarkers, currentTopicMetadata);

            if (cleanupCoordinator != null) {
                cleanupCoordinator.validateRecoveryOffsets(
                        topic, currentTopicMetadata.getTopicId(), partitionMap);
            }
            loadVerifiedTopicMetadata();

            List<TopicPartition> partitionsToAssign = new ArrayList<>();
            for (Integer partition : partitionMap.keySet()) {
                partitionsToAssign.add(new TopicPartition(topic, partition));
            }

            Map<TopicPartition, Long> beginningOffsets =
                    consumer.beginningOffsets(partitionsToAssign);
            Map<TopicPartition, Long> replayEndOffsets = consumer.endOffsets(partitionsToAssign);
            validateRecoveryOffsets(partitionMap, beginningOffsets, replayEndOffsets);

            consumer.assign(partitionsToAssign);
            partitionMap.forEach(
                    (partition, offset) ->
                            consumer.seek(new TopicPartition(topic, partition), offset));

            Map<TopicPartition, Long> lastPositions = consumerPositions(replayEndOffsets);
            long lastProgressNanos = System.nanoTime();
            while (!hasReachedReplayEnd(lastPositions, replayEndOffsets)) {
                ConsumerRecords<String, ActionState> records = consumer.poll(CONSUMER_POLL_TIMEOUT);

                // Deserialization failures throw from poll() itself and are handled by the
                // outer catch, so records here are always fully deserialized.
                for (ConsumerRecord<String, ActionState> record : records) {
                    TopicPartition recordPartition =
                            new TopicPartition(record.topic(), record.partition());
                    Long replayEndOffset = replayEndOffsets.get(recordPartition);
                    if (replayEndOffset == null || record.offset() >= replayEndOffset) {
                        continue;
                    }
                    if (!ActionStateUtil.isKeyRetained(ownershipFilter, record.key())) {
                        continue;
                    }
                    if (record.value() == null) {
                        // Tombstone record - remove the key from cache
                        actionStates.remove(record.key());
                    } else {
                        actionStates.put(record.key(), record.value());
                    }
                }

                Map<TopicPartition, Long> currentPositions = consumerPositions(replayEndOffsets);
                if (!currentPositions.equals(lastPositions)) {
                    lastPositions = currentPositions;
                    lastProgressNanos = System.nanoTime();
                } else if (System.nanoTime() - lastProgressNanos
                        >= REBUILD_PROGRESS_TIMEOUT.toNanos()) {
                    throw new IllegalStateException(
                            String.format(
                                    "Kafka action-state replay made no progress for %s; current positions are %s and target end offsets are %s",
                                    REBUILD_PROGRESS_TIMEOUT, currentPositions, replayEndOffsets));
                }
            }
            loadVerifiedTopicMetadata();
            LOG.info("Completed rebuilding state, recovered {} states", actionStates.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild state from Kafka", e);
        }
    }

    private Map<Integer, Long> mergeAndValidateRecoveryMarkers(
            List<Object> recoveryMarkers, KafkaTopicMetadata topicMetadata) {
        Map<Integer, Long> mergedOffsets = new HashMap<>();
        boolean foundVersionedMarker = false;
        boolean foundLegacyMarker = false;

        for (Object marker : recoveryMarkers) {
            if (marker == null) {
                throw new IllegalArgumentException(
                        "Kafka action-state recovery marker must not be null");
            }
            Map<Integer, Long> offsets;
            if (marker instanceof KafkaActionStateRecoveryMarker) {
                foundVersionedMarker = true;
                KafkaActionStateRecoveryMarker versionedMarker =
                        (KafkaActionStateRecoveryMarker) marker;
                validateVersionedMarker(versionedMarker, topicMetadata);
                offsets = versionedMarker.getOffsets();
            } else if (marker instanceof Map) {
                foundLegacyMarker = true;
                offsets = readLegacyOffsets((Map<?, ?>) marker);
                validatePartitionSet(offsets.keySet(), topicMetadata.getPartitions());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Kafka action-state recovery marker: "
                                + marker.getClass().getName());
            }

            offsets.forEach(
                    (partition, offset) -> mergedOffsets.merge(partition, offset, Math::min));
        }

        if (foundVersionedMarker && foundLegacyMarker) {
            throw new IllegalArgumentException(
                    "Cannot restore from a mixture of versioned and legacy Kafka recovery markers");
        }
        if (cleanupCoordinator != null && foundLegacyMarker) {
            throw new IllegalArgumentException(
                    "Checkpoint-aligned cleanup requires versioned Kafka recovery markers");
        }
        return mergedOffsets;
    }

    private void validateVersionedMarker(
            KafkaActionStateRecoveryMarker marker, KafkaTopicMetadata topicMetadata) {
        if (marker.getSchemaVersion() != KafkaActionStateRecoveryMarker.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unsupported Kafka action-state recovery marker schema %d, expected %d",
                            marker.getSchemaVersion(),
                            KafkaActionStateRecoveryMarker.CURRENT_SCHEMA_VERSION));
        }
        if (!topic.equals(marker.getTopic())) {
            throw new IllegalStateException(
                    String.format(
                            "Kafka action-state recovery marker references topic %s, but the configured topic is %s",
                            marker.getTopic(), topic));
        }
        if (!topicMetadata.getTopicId().equals(marker.getTopicId())) {
            throw new IllegalStateException(
                    String.format(
                            "Kafka action-state topic %s has ID %s, but the recovery marker expects %s; the topic may have been recreated",
                            topic, topicMetadata.getTopicId(), marker.getTopicId()));
        }
        validatePartitionSet(marker.getOffsets().keySet(), topicMetadata.getPartitions());
    }

    private Map<Integer, Long> readLegacyOffsets(Map<?, ?> marker) {
        Map<Integer, Long> offsets = new HashMap<>();
        for (Map.Entry<?, ?> entry : marker.entrySet()) {
            if (!(entry.getKey() instanceof Integer) || !(entry.getValue() instanceof Long)) {
                throw new IllegalArgumentException(
                        "Legacy Kafka action-state recovery markers must map Integer partitions to Long offsets");
            }
            int partition = (Integer) entry.getKey();
            long offset = (Long) entry.getValue();
            if (partition < 0 || offset < 0) {
                throw new IllegalArgumentException(
                        "Legacy Kafka action-state recovery marker partitions and offsets must be non-negative");
            }
            offsets.put(partition, offset);
        }
        return offsets;
    }

    private void validatePartitionSet(Set<Integer> markerPartitions, Set<Integer> topicPartitions) {
        if (!markerPartitions.equals(topicPartitions)) {
            throw new IllegalStateException(
                    String.format(
                            "Kafka action-state recovery marker contains partitions %s, but topic %s currently has partitions %s",
                            markerPartitions, topic, topicPartitions));
        }
    }

    private void validateRecoveryOffsets(
            Map<Integer, Long> requestedOffsets,
            Map<TopicPartition, Long> beginningOffsets,
            Map<TopicPartition, Long> endOffsets) {
        requestedOffsets.forEach(
                (partition, requestedOffset) -> {
                    TopicPartition topicPartition = new TopicPartition(topic, partition);
                    Long beginningOffset = beginningOffsets.get(topicPartition);
                    Long endOffset = endOffsets.get(topicPartition);
                    if (beginningOffset == null || endOffset == null) {
                        throw new IllegalStateException(
                                String.format(
                                        "Kafka did not return beginning and end offsets for %s",
                                        topicPartition));
                    }
                    if (requestedOffset < beginningOffset || requestedOffset > endOffset) {
                        throw new IllegalStateException(
                                String.format(
                                        "Cannot rebuild Kafka action state for %s: requested offset %d is outside the available range [%d, %d]",
                                        topicPartition,
                                        requestedOffset,
                                        beginningOffset,
                                        endOffset));
                    }
                });
    }

    private Map<TopicPartition, Long> consumerPositions(
            Map<TopicPartition, Long> replayEndOffsets) {
        Map<TopicPartition, Long> positions = new HashMap<>();
        replayEndOffsets.forEach(
                (partition, replayEndOffset) ->
                        positions.put(
                                partition,
                                Math.min(consumer.position(partition), replayEndOffset)));
        return positions;
    }

    private boolean hasReachedReplayEnd(
            Map<TopicPartition, Long> positions, Map<TopicPartition, Long> replayEndOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : replayEndOffsets.entrySet()) {
            if (positions.get(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setOwnershipFilter(IntPredicate ownershipFilter) {
        this.ownershipFilter = ownershipFilter;
    }

    @Override
    public void pruneState(Object key, long seqNum) {
        LOG.debug("Pruning state for key: {} up to sequence number: {}", key, seqNum);

        // Collect state keys belonging to this key with sequence number <= seqNum.
        List<String> keysToPrune = new ArrayList<>();
        for (String stateKey : actionStates.keySet()) {
            if (ActionStateUtil.matchesBusinessKeyWithSeqNum(
                    stateKey, key, stateSeqNum -> stateSeqNum <= seqNum)) {
                keysToPrune.add(stateKey);
            }
        }

        // Send tombstones to Kafka so log compaction can reclaim storage; opt-in because
        // tombstones break replay when restoring a checkpoint/savepoint older than the prune
        // (see KAFKA_ACTION_STATE_TOMBSTONE_ENABLED). Send failures surface asynchronously,
        // so report them via callback; the records then persist until manual cleanup.
        if (tombstoneEnabled && producer != null && !keysToPrune.isEmpty()) {
            try {
                for (String stateKey : keysToPrune) {
                    producer.send(
                            new ProducerRecord<>(topic, stateKey, null),
                            (metadata, exception) -> {
                                if (exception != null) {
                                    LOG.warn(
                                            "Failed to send tombstone record for state key: {}. "
                                                    + "The record will persist in the topic "
                                                    + "until manual cleanup.",
                                            stateKey,
                                            exception);
                                }
                            });
                }
                LOG.debug(
                        "Queued {} tombstone records to Kafka for key: {}",
                        keysToPrune.size(),
                        key);
            } catch (Exception e) {
                LOG.warn(
                        "Failed to send tombstone records to Kafka for key: {}. "
                                + "Records will persist in the topic until manual cleanup.",
                        key,
                        e);
            }
        }

        // Remove from in-memory cache (always, regardless of tombstone success)
        actionStates.keySet().removeAll(keysToPrune);

        LOG.debug("Pruned state for key: {} up to sequence number: {}", key, seqNum);
    }

    /** Returns a versioned marker containing the Kafka topic identity and current end offsets. */
    @Override
    public Object getRecoveryMarker() {
        try {
            KafkaTopicMetadata currentTopicMetadata = loadVerifiedTopicMetadata();
            List<TopicPartition> partitions = new ArrayList<>();
            for (Integer partition : currentTopicMetadata.getPartitions()) {
                partitions.add(new TopicPartition(topic, partition));
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<Integer, Long> recoveryOffsets = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                recoveryOffsets.put(entry.getKey().partition(), entry.getValue());
            }
            if (!recoveryOffsets.keySet().equals(currentTopicMetadata.getPartitions())) {
                throw new IllegalStateException(
                        String.format(
                                "Kafka topic %s returned end offsets for partitions %s, expected %s",
                                topic,
                                recoveryOffsets.keySet(),
                                currentTopicMetadata.getPartitions()));
            }
            return new KafkaActionStateRecoveryMarker(
                    topic, currentTopicMetadata.getTopicId(), recoveryOffsets);
        } catch (Exception e) {
            LOG.error("Failed to verify Kafka topic: {}", topic, e);
            throw new RuntimeException("Failed to verify Kafka topic", e);
        }
    }

    private KafkaTopicMetadata loadVerifiedTopicMetadata() throws Exception {
        KafkaTopicMetadata currentTopicMetadata = topicMetadataLoader.load();
        if (!topicMetadata.getTopicId().equals(currentTopicMetadata.getTopicId())
                || !topicMetadata.getPartitions().equals(currentTopicMetadata.getPartitions())) {
            throw new IllegalStateException(
                    String.format(
                            "Kafka action-state topic %s changed while the job was running; expected ID %s and partitions %s but found ID %s and partitions %s",
                            topic,
                            topicMetadata.getTopicId(),
                            topicMetadata.getPartitions(),
                            currentTopicMetadata.getTopicId(),
                            currentTopicMetadata.getPartitions()));
        }
        return currentTopicMetadata;
    }

    @Override
    public void close() throws Exception {
        // Catching Throwable rather than Exception is what keeps the consumer close reachable when
        // the producer close fails with an Error, and what keeps that first failure the one the
        // caller sees — a later consumer failure rides along as suppressed instead of replacing it.
        Throwable firstException = null;
        if (producer != null) {
            try {
                producer.close();
            } catch (Throwable t) {
                firstException = t;
            }
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Throwable t) {
                firstException = ExceptionUtils.firstOrSuppressed(t, firstException);
            }
        }
        if (cleanupCoordinator != null) {
            try {
                cleanupCoordinator.close();
            } catch (Throwable t) {
                firstException = ExceptionUtils.firstOrSuppressed(t, firstException);
            }
        }
        if (firstException != null) {
            ExceptionUtils.rethrowException(firstException);
        }
    }

    private KafkaTopicMetadata loadTopicMetadata() throws Exception {
        try (AdminClient adminClient = AdminClient.create(createCommonKafkaConfig())) {
            DescribeTopicsResult result = adminClient.describeTopics(List.of(topic));
            TopicDescription description =
                    result.allTopicNames()
                            .get(DEFAULT_FUTURE_GET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .get(topic);
            if (description == null) {
                throw new IllegalStateException("Kafka topic does not exist: " + topic);
            }
            Set<Integer> partitions = new HashSet<>();
            description.partitions().forEach(partition -> partitions.add(partition.partition()));
            return new KafkaTopicMetadata(description.topicId().toString(), partitions);
        }
    }

    private static KafkaTopicMetadata testTopicMetadata(
            Consumer<String, ActionState> consumer, String topic) {
        Set<Integer> partitions = new HashSet<>();
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
        if (partitionInfos != null) {
            partitionInfos.forEach(partition -> partitions.add(partition.partition()));
        }
        return new KafkaTopicMetadata("test-topic-id:" + topic, partitions);
    }

    private void maybeCreateTopic() {
        try (AdminClient adminClient = AdminClient.create(createCommonKafkaConfig())) {
            ListTopicsResult topics = adminClient.listTopics();
            if (!topics.names()
                    .get(DEFAULT_FUTURE_GET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .contains(topic)) {
                NewTopic newTopic =
                        new NewTopic(
                                topic,
                                agentConfiguration.get(KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS),
                                agentConfiguration
                                        .get(KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR)
                                        .shortValue());
                // enable topic compaction
                newTopic.configs(Map.of("cleanup.policy", "compact"));
                adminClient.createTopics(List.of(newTopic)).all().get();
                LOG.info("Created Kafka topic: {}", topic);
            } else {
                LOG.info("Kafka topic {} already exists", topic);
            }
        } catch (Exception e) {
            LOG.error("Failed to create or verify Kafka topic: {}", topic, e);
            throw new RuntimeException("Failed to create or verify Kafka topic", e);
        }
    }

    private Properties createCommonKafkaConfig() {
        Properties props = new Properties();
        props.put(BOOTSTRAP_SERVERS_CONFIG, agentConfiguration.get(KAFKA_BOOTSTRAP_SERVERS));
        return props;
    }

    private static void closeAfterInitializationFailure(
            AutoCloseable resource, Throwable initializationFailure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            initializationFailure.addSuppressed(closeFailure);
        }
    }

    private Properties createProducerProp() {
        Properties producerProps = new Properties();
        producerProps.putAll(createCommonKafkaConfig());
        producerProps.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(VALUE_SERIALIZER_CLASS_CONFIG, ActionStateKafkaSeder.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(
                PARTITIONER_CLASS_CONFIG,
                "org.apache.flink.agents.runtime.actionstate.ActionStateKeyPartitioner");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return producerProps;
    }

    @VisibleForTesting
    Properties createConsumerProp() {
        Properties consumerProps = new Properties();

        consumerProps.putAll(createCommonKafkaConfig());
        consumerProps.put(CLIENT_ID_CONFIG, "action-state-rebuild-consumer");
        consumerProps.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(VALUE_DESERIALIZER_CLASS_CONFIG, ActionStateKafkaSeder.class.getName());
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "action-state-rebuild-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        consumerProps.put(ENABLE_AUTO_COMMIT_CONFIG, false);

        return consumerProps;
    }

    static final class KafkaTopicMetadata {
        private final String topicId;
        private final Set<Integer> partitions;

        KafkaTopicMetadata(String topicId, Set<Integer> partitions) {
            this.topicId = Preconditions.checkNotNull(topicId, "Topic ID must not be null");
            this.partitions =
                    Collections.unmodifiableSet(
                            new HashSet<>(
                                    Preconditions.checkNotNull(
                                            partitions, "Partitions must not be null")));
        }

        String getTopicId() {
            return topicId;
        }

        Set<Integer> getPartitions() {
            return partitions;
        }
    }

    @FunctionalInterface
    private interface TopicMetadataLoader {
        KafkaTopicMetadata load() throws Exception;
    }
}
