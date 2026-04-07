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

    /**
     * The config parameter determines whether to enable pretty-printed JSON format for FileEvent
     * logs.
     */
    public static final ConfigOption<Boolean> PRETTY_PRINT =
            new ConfigOption<>("prettyPrint", Boolean.class, false);

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
     * The global event log level controlling the default verbosity for all event types. Valid
     * values are "OFF", "STANDARD", and "VERBOSE". Defaults to "STANDARD".
     */
    public static final ConfigOption<String> EVENT_LOG_LEVEL =
            new ConfigOption<>("event-log.level", String.class, "STANDARD");

    /**
     * The maximum string length for event payloads when logging at STANDARD level. Strings
     * exceeding this length will be truncated. Defaults to 2000.
     */
    public static final ConfigOption<Integer> EVENT_LOG_MAX_STRING_LENGTH =
            new ConfigOption<>("event-log.standard.max-string-length", Integer.class, 2000);

    /**
     * The maximum number of array elements to include in event payloads when logging at STANDARD
     * level. Arrays exceeding this size will be truncated. Defaults to 20.
     */
    public static final ConfigOption<Integer> EVENT_LOG_MAX_ARRAY_ELEMENTS =
            new ConfigOption<>("event-log.standard.max-array-elements", Integer.class, 20);

    /**
     * The maximum nesting depth for event payloads when logging at STANDARD level. Objects deeper
     * than this level will be summarized. Defaults to 5.
     */
    public static final ConfigOption<Integer> EVENT_LOG_MAX_DEPTH =
            new ConfigOption<>("event-log.standard.max-depth", Integer.class, 5);
}
