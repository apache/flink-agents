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
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Kafka integration tests for checkpoint-aligned action-state cleanup. */
class KafkaActionStateCleanupCoordinatorIntegrationTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Test
    void testCommitsDeletesVerifiesAndRecoversBoundary() throws Exception {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the Kafka cleanup integration test");

        try (KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0")) {
            kafka.start();
            String dataTopic = "action-state";
            String controlTopic = "action-state-control";
            Properties common = new Properties();
            common.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());

            String topicId;
            try (AdminClient admin = AdminClient.create(common)) {
                admin.createTopics(List.of(new NewTopic(dataTopic, 2, (short) 1)))
                        .all()
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                TopicDescription description =
                        admin.describeTopics(List.of(dataTopic))
                                .allTopicNames()
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .get(dataTopic);
                topicId = description.topicId().toString();
            }

            Properties producerProperties = new Properties();
            producerProperties.putAll(common);
            producerProperties.put(
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerProperties.put(
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
                for (int offset = 0; offset < 3; offset++) {
                    producer.send(
                                    new ProducerRecord<>(
                                            dataTopic, 0, "p0-" + offset, "value-" + offset))
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                for (int offset = 0; offset < 2; offset++) {
                    producer.send(
                                    new ProducerRecord<>(
                                            dataTopic, 1, "p1-" + offset, "value-" + offset))
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            }

            KafkaActionStateCleanupPlan plan =
                    KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                            "checkpoint-42",
                            List.of(
                                    new KafkaActionStateRecoveryMarker(
                                            dataTopic, topicId, Map.of(0, 2L, 1, 1L))));
            AgentConfiguration configuration = new AgentConfiguration();
            configuration.set(KAFKA_BOOTSTRAP_SERVERS, kafka.getBootstrapServers());
            configuration.set(KAFKA_ACTION_STATE_TOPIC, dataTopic);
            configuration.set(KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, controlTopic);

            try (KafkaActionStateCleanupCoordinator coordinator =
                    KafkaActionStateCleanupCoordinator.create(configuration)) {
                assertThat(coordinator.apply(plan))
                        .isEqualTo(KafkaActionStateCleanupCoordinator.Status.APPLIED);
            }

            try (KafkaActionStateCleanupCoordinator recoveryCoordinator =
                    KafkaActionStateCleanupCoordinator.createForRecovery(configuration)) {
                assertThat(
                                recoveryCoordinator.getCommittedBoundary(
                                        dataTopic, topicId, Set.of(0, 1)))
                        .isEqualTo(Map.of(0, 2L, 1, 1L));
            }

            try (AdminClient admin = AdminClient.create(common)) {
                Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
                requests.put(new TopicPartition(dataTopic, 0), OffsetSpec.earliest());
                requests.put(new TopicPartition(dataTopic, 1), OffsetSpec.earliest());
                Map<TopicPartition, Long> beginningOffsets = new HashMap<>();
                admin.listOffsets(requests)
                        .all()
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .forEach(
                                (partition, result) ->
                                        beginningOffsets.put(partition, result.offset()));
                assertThat(beginningOffsets)
                        .containsEntry(new TopicPartition(dataTopic, 0), 2L)
                        .containsEntry(new TopicPartition(dataTopic, 1), 1L);
            }
        }
    }
}
