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
package org.apache.flink.agents.api.configuration;

/** The set of configuration options for agents parameters. */
public class AgentConfigOptions {

    /** The config parameter specifies the directory for the FileEvent file. */
    public static final ConfigOption<String> BASE_LOG_DIR =
            new ConfigOption<>("baseLogDir", String.class, null);

    /** The config parameter specifies the backend for action state store. */
    public static final ConfigOption<String> ACTION_STATE_STORE_BACKEND =
            new ConfigOption<>("actionStateStoreBackend", String.class, null);

    /** The config parameter specifies the Kafka bootstrap server. */
    public static final ConfigOption<String> KAFKA_BOOTSTRAP_SERVERS =
            new ConfigOption<>("kafkaBootstrapServers", String.class, "localhost:9092");

    /** The config parameter specifies the Kafka topic for action state. */
    public static final ConfigOption<String> KAFKA_ACTION_STATE_TOPIC =
            new ConfigOption<>("kafkaActionStateTopic", String.class, null);

    /** The config parameter specifies the number of partitions for the Kafka action state topic. */
    public static final ConfigOption<Integer> KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS =
            new ConfigOption<>("kafkaActionStateTopicNumPartitions", Integer.class, 64);

    /** The config parameter specifies the replication factor for the Kafka action state topic. */
    public static final ConfigOption<Integer> KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR =
            new ConfigOption<>("kafkaActionStateTopicReplicationFactor", Integer.class, 1);

    /** The config parameter specifies the unique identifier of job. */
    public static final ConfigOption<String> JOB_IDENTIFIER =
            new ConfigOption<>("job-identifier", String.class, null);

    /**
     * The default event log level applied to all event types unless overridden.
     *
     * <p>Valid values: "OFF", "STANDARD", "VERBOSE". Defaults to "STANDARD".
     */
    public static final ConfigOption<String> EVENT_LOG_LEVEL =
            new ConfigOption<>("eventLogLevel", String.class, "STANDARD");

    /**
     * Per-event-type log level overrides as a comma-separated string.
     *
     * <p>Format: {@code "EventTypeName=LEVEL,EventTypeName=LEVEL"}.
     *
     * <p>Example: {@code "ChatRequestEvent=VERBOSE,ContextRetrievalRequestEvent=OFF"}
     */
    public static final ConfigOption<String> EVENT_LOG_LEVELS =
            new ConfigOption<>("eventLogLevels", String.class, null);

    /**
     * Maximum character length for string fields in STANDARD log level output.
     *
     * <p>String fields exceeding this limit are truncated with a {@code "... [truncated, N chars
     * total]"} marker. VERBOSE level ignores this setting and logs full content. A value of 0 or
     * negative disables truncation.
     *
     * <p>Defaults to 1024.
     */
    public static final ConfigOption<Integer> EVENT_LOG_MAX_FIELD_LENGTH =
            new ConfigOption<>("eventLogMaxFieldLength", Integer.class, 1024);
}
