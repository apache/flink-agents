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

    /** The config parameter specifies the Fluss bootstrap servers. */
    public static final ConfigOption<String> FLUSS_BOOTSTRAP_SERVERS =
            new ConfigOption<>("flussBootstrapServers", String.class, "localhost:9123");

    /** The config parameter specifies the Fluss database for action state. */
    public static final ConfigOption<String> FLUSS_ACTION_STATE_DATABASE =
            new ConfigOption<>("flussActionStateDatabase", String.class, "flink_agents");

    /** The config parameter specifies the Fluss table name for action state. */
    public static final ConfigOption<String> FLUSS_ACTION_STATE_TABLE =
            new ConfigOption<>("flussActionStateTable", String.class, "action_state");

    /** The config parameter specifies the number of buckets for the Fluss action state table. */
    public static final ConfigOption<Integer> FLUSS_ACTION_STATE_TABLE_BUCKETS =
            new ConfigOption<>("flussActionStateTableBuckets", Integer.class, 8);

    /** The config parameter specifies the authentication protocol for Fluss client. */
    public static final ConfigOption<String> FLUSS_SECURITY_PROTOCOL =
            new ConfigOption<>("flussSecurityProtocol", String.class, "PLAINTEXT");

    /** The config parameter specifies the SASL mechanism for Fluss authentication. */
    public static final ConfigOption<String> FLUSS_SASL_MECHANISM =
            new ConfigOption<>("flussSaslMechanism", String.class, "PLAIN");

    /**
     * The config parameter specifies the JAAS configuration string for Fluss SASL authentication.
     */
    public static final ConfigOption<String> FLUSS_SASL_JAAS_CONFIG =
            new ConfigOption<>("flussSaslJaasConfig", String.class, null);

    /** The config parameter specifies the username for Fluss SASL authentication. */
    public static final ConfigOption<String> FLUSS_SASL_USERNAME =
            new ConfigOption<>("flussSaslUsername", String.class, null);

    /** The config parameter specifies the password for Fluss SASL authentication. */
    public static final ConfigOption<String> FLUSS_SASL_PASSWORD =
            new ConfigOption<>("flussSaslPassword", String.class, null);

    /** The config parameter specifies the unique identifier of job. */
    public static final ConfigOption<String> JOB_IDENTIFIER =
            new ConfigOption<>("job-identifier", String.class, null);
}
