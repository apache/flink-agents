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
import org.apache.flink.agents.plan.Action;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Future;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;

/**
 * An implementation of ActionStateStore that uses Kafka as the backend storage for action states.
 * This class provides methods to put, get, and retrieve all action states associated with a given
 * key and action.
 */
public class KafkaActionStateStore implements ActionStateStore {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaActionStateStore.class);
    private static final String DEFAULT_TOPIC = "flink-agents-action-state";
    private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofMillis(1000);

    // In memory action state for quick state retrieval
    private final Map<String, ActionState> actionStates;

    // Kafka producer and consumer
    private final Producer<String, String> producer;
    private final Consumer<String, String> consumer;
    private final String topic;
    private final ObjectMapper objectMapper;

    // Recovery marker to track the latest processed offset
    private final Map<TopicPartition, Long> recoveryMarker;

    @VisibleForTesting
    KafkaActionStateStore(
            Map<String, ActionState> actionStates,
            Producer<String, String> producer,
            Consumer<String, String> consumer,
            String topic) {
        this.actionStates = actionStates;
        this.producer = producer;
        this.consumer = consumer;
        this.topic = topic;
        this.objectMapper = new ObjectMapper();
        this.recoveryMarker = new HashMap<>();
    }

    /** Constructs a new KafkaActionStateStore with default configuration. */
    public KafkaActionStateStore() {
        this(createDefaultKafkaConfig());
    }

    /** Constructs a new KafkaActionStateStore with custom Kafka configuration. */
    public KafkaActionStateStore(Properties kafkaConfig) {
        this.actionStates = new HashMap<>();
        this.topic = kafkaConfig.getProperty("topic", DEFAULT_TOPIC);
        this.objectMapper = new ObjectMapper();
        this.recoveryMarker = new HashMap<>();

        // Create producer
        Properties producerProps = new Properties();
        producerProps.putAll(kafkaConfig);
        producerProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        this.producer = new KafkaProducer<>(producerProps);

        // Create consumer
        Properties consumerProps = new Properties();
        consumerProps.putAll(kafkaConfig);
        consumerProps.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "flink-agents-action-state-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumer = new KafkaConsumer<>(consumerProps);
    }

    private static Properties createDefaultKafkaConfig() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, DEFAULT_BOOTSTRAP_SERVERS);
        props.put("topic", DEFAULT_TOPIC);
        return props;
    }

    @Override
    public void put(Object key, Action action, Event event, ActionState state) throws IOException {
        String stateKey = generateKey(key, action, event);
        actionStates.put(stateKey, state);

        // Serialize and send to Kafka
        try {
            ActionStateRecord record =
                    new ActionStateRecord(
                            key.toString(), action.getName(), event.getClass().getName(), state);
            String serializedState = objectMapper.writeValueAsString(record);

            ProducerRecord<String, String> kafkaRecord =
                    new ProducerRecord<>(topic, stateKey, serializedState);
            Future<RecordMetadata> future = producer.send(kafkaRecord);

            LOG.debug("Sent action state to Kafka for key: {}", stateKey);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize action state for key: {}", stateKey, e);
            throw new RuntimeException("Failed to serialize action state", e);
        }
    }

    @Override
    public ActionState get(Object key, Action action, Event event) throws IOException {
        return actionStates.get(generateKey(key, action, event));
    }

    @Override
    public void rebuildState(Object recoveryMarker) {
        LOG.info("Rebuilding state for key: {}", key);

        try {
            // Subscribe to the topic
            consumer.subscribe(Collections.singletonList(topic));

            // Poll for records and rebuild state
            long endTime = System.currentTimeMillis() + 30000; // 30 second timeout
            while (System.currentTimeMillis() < endTime) {
                ConsumerRecords<String, String> records = consumer.poll(CONSUMER_POLL_TIMEOUT);

                if (records.isEmpty()) {
                    continue;
                }

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        ActionStateRecord stateRecord =
                                objectMapper.readValue(record.value(), ActionStateRecord.class);

                        // Only rebuild states for the specified key
                        if (key.toString().equals(stateRecord.getKey())) {
                            actionStates.put(record.key(), stateRecord.getActionState());

                            // Update recovery marker
                            TopicPartition partition =
                                    new TopicPartition(record.topic(), record.partition());
                            recoveryMarker.put(partition, record.offset());
                        }
                    } catch (JsonProcessingException e) {
                        LOG.warn(
                                "Failed to deserialize action state record: {}", record.value(), e);
                    }
                }

                // Commit offsets manually
                consumer.commitSync();
            }

            LOG.info(
                    "Completed rebuilding state for key: {}, recovered {} states",
                    key,
                    actionStates.size());
        } catch (Exception e) {
            LOG.error("Failed to rebuild state for key: {}", key, e);
            throw new RuntimeException("Failed to rebuild state from Kafka", e);
        }
    }

    @Override
    public void cleanUpState() {
        LOG.info("Cleaning up Kafka action state store");
        // Close Kafka clients
        if (producer != null) {
            producer.close();
        }
        if (consumer != null) {
            consumer.close();
        }
        // Clear in-memory state
        actionStates.clear();
        recoveryMarker.clear();
    }

    @Override
    public Object getRecoveryMarker() {
        return new HashMap<>(recoveryMarker);
    }

    /** Internal class to represent an action state record for Kafka serialization. */
    private static class ActionStateRecord {
        private String key;
        private String actionName;
        private String eventType;
        private ActionState actionState;

        // Default constructor for Jackson
        public ActionStateRecord() {}

        public ActionStateRecord(
                String key, String actionName, String eventType, ActionState actionState) {
            this.key = key;
            this.actionName = actionName;
            this.eventType = eventType;
            this.actionState = actionState;
        }

        // Getters and setters

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getActionName() {
            return actionName;
        }

        public void setActionName(String actionName) {
            this.actionName = actionName;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public ActionState getActionState() {
            return actionState;
        }

        public void setActionState(ActionState actionState) {
            this.actionState = actionState;
        }
    }
}
