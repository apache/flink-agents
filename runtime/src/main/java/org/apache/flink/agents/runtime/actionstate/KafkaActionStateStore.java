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

import org.apache.beam.sdk.util.Preconditions;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;
import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG;
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
    private static final Logger LOG = LoggerFactory.getLogger(KafkaActionStateStore.class);
    private static final Long DEFAULT_FUTURE_GET_TIMEOUT_MS = 100L;

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

    @VisibleForTesting
    KafkaActionStateStore(
            Map<String, ActionState> actionStates,
            AgentConfiguration agentConfiguration,
            Producer<String, ActionState> producer,
            Consumer<String, ActionState> consumer,
            String topic) {
        this.actionStates = actionStates;
        this.producer = producer;
        this.consumer = consumer;
        this.topic = topic;
        this.latestKeySeqNum = new HashMap<>();
        this.agentConfiguration = agentConfiguration;
    }

    /** Constructs a new KafkaActionStateStore with custom Kafka configuration. */
    public KafkaActionStateStore(AgentConfiguration agentConfiguration) {
        this.actionStates = new HashMap<>();
        this.latestKeySeqNum = new HashMap<>();
        this.agentConfiguration = agentConfiguration;
        this.topic =
                Preconditions.checkArgumentNotNull(
                        agentConfiguration.get(KAFKA_ACTION_STATE_TOPIC),
                        "Kafka action state topic must be configured");
        // create the topic if not exists
        maybeCreateTopic();
        Properties producerProp = createProducerProp();
        this.producer = new KafkaProducer<>(producerProp);
        Properties consumerProp = createConsumerProp();
        this.consumer = new KafkaConsumer<>(consumerProp);
        consumer.subscribe(Collections.singletonList(topic));
        LOG.info("Initialized KafkaActionStateStore with topic: {}", topic);
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws Exception {
        if (producer == null) {
            LOG.error("Producer is null, cannot put action state to Kafka");
            return;
        }

        String stateKey = generateKey(key, seqNum, action, event);
        try {
            ProducerRecord<String, ActionState> kafkaRecord =
                    new ProducerRecord<>(topic, stateKey, state);
            producer.send(kafkaRecord);
            LOG.debug("Sent action state to Kafka for key: {}", stateKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send action state to Kafka", e);
        }
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws Exception {
        String stateKey = generateKey(key, seqNum, action, event);

        boolean hasDivergence = checkDivergence(key.toString(), seqNum);

        if (!actionStates.containsKey(stateKey) || hasDivergence) {
            actionStates
                    .entrySet()
                    .removeIf(
                            entry -> {
                                // Extract key and sequence number from the state key
                                try {
                                    List<String> parts = ActionStateUtil.parseKey(entry.getKey());
                                    if (parts.size() >= 2) {
                                        long stateSeqNum = Long.parseLong(parts.get(1));
                                        // clean up any states with sequence number greater than
                                        // the requested seqNum
                                        return stateSeqNum > seqNum;
                                    }
                                } catch (NumberFormatException e) {
                                    LOG.warn(
                                            "Failed to parse sequence number from state key: {}",
                                            stateKey);
                                }
                                return false;
                            });
        }

        return actionStates.get(stateKey);
    }

    private boolean checkDivergence(String key, long seqNum) {
        return actionStates.keySet().stream().filter(k -> k.startsWith(key + "_" + seqNum)).count()
                > 1;
    }

    @Override
    public void rebuildState(List<Object> recoveryMarkers) {
        LOG.info("Rebuilding state from {} recovery markers", recoveryMarkers.size());

        try {
            Map<Integer, Long> partitionMap = new HashMap<>();
            // Process recovery markers to get the smallest offsets for each partition
            for (Object marker : recoveryMarkers) {
                if (marker instanceof Map) {
                    Map<Integer, Long> markerMap = (Map<Integer, Long>) marker;
                    for (Map.Entry<Integer, Long> entry : markerMap.entrySet()) {
                        Long offset =
                                partitionMap.computeIfPresent(
                                        entry.getKey(),
                                        (key, value) -> Math.min(value, entry.getValue()));
                        partitionMap.put(
                                entry.getKey(), offset == null ? entry.getValue() : offset);
                    }
                }
            }
            partitionMap.forEach(
                    (partition, offset) ->
                            consumer.seek(new TopicPartition(topic, partition), offset));

            // Poll for records and rebuild state until the latest offsets
            while (true) {
                ConsumerRecords<String, ActionState> records = consumer.poll(CONSUMER_POLL_TIMEOUT);

                if (records.isEmpty()) {
                    // reaches to the end of the topic
                    break;
                }

                for (ConsumerRecord<String, ActionState> record : records) {
                    try {
                        actionStates.put(record.key(), record.value());
                    } catch (Exception e) {
                        LOG.warn(
                                "Failed to deserialize action state record: {}",
                                record.value().toString(),
                                e);
                    }
                }

                // Commit offsets manually
                consumer.commitSync();
            }
            LOG.info("Completed rebuilding state, recovered {} states", actionStates.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild state from Kafka", e);
        }
    }

    @Override
    public void pruneState(Object key, long seqNum) {
        LOG.info("Pruning state for key: {} up to sequence number: {}", key, seqNum);

        // Remove states from in-memory cache for this key up to the specified sequence
        // number
        actionStates
                .entrySet()
                .removeIf(
                        entry -> {
                            String stateKey = entry.getKey();
                            // Extract key and sequence number from the state key
                            // State key format: "key_seqNum_action_event"
                            if (stateKey.startsWith(key.toString() + "_")) {
                                try {
                                    List<String> parts = ActionStateUtil.parseKey(stateKey);
                                    if (parts.size() >= 2) {
                                        long stateSeqNum = Long.parseLong(parts.get(1));
                                        return stateSeqNum <= seqNum;
                                    }
                                } catch (NumberFormatException e) {
                                    LOG.warn(
                                            "Failed to parse sequence number from state key: {}",
                                            stateKey);
                                }
                            }
                            return false;
                        });

        LOG.debug("Pruned state for key: {} up to sequence number: {}", key, seqNum);
    }

    /**
     * In kafka's implementation, we always return the end offsets of each partitions as recovery
     * markers.
     */
    @Override
    public Object getRecoveryMarker() {
        Map<Integer, Long> recoveryMarker = new HashMap<>();

        try {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo partitionInfo : consumer.partitionsFor(topic)) {
                partitions.add(new TopicPartition(topic, partitionInfo.partition()));
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                recoveryMarker.put(entry.getKey().partition(), entry.getValue());
            }
        } catch (Exception e) {
            LOG.error("Failed to verify Kafka topic: {}", topic, e);
            throw new RuntimeException("Failed to verify Kafka topic", e);
        }

        return recoveryMarker;
    }

    @Override
    public void close() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (consumer != null) {
            consumer.close();
        }
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

    private Properties createConsumerProp() {
        Properties consumerProps = new Properties();

        consumerProps.putAll(createCommonKafkaConfig());
        consumerProps.put(CLIENT_ID_CONFIG, "action-state-rebuild-consumer");
        consumerProps.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(VALUE_DESERIALIZER_CLASS_CONFIG, ActionStateKafkaSeder.class.getName());
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "action-state-rebuild-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return consumerProps;
    }
}
