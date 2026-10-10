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

import org.apache.flink.agents.api.logger.LoggerType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The set of configuration options for agents parameters. */
public class AgentConfigOptions {

    /** Behaviour when a trigger condition throws or returns a non-Boolean at evaluation time. */
    public enum ConditionEvaluationFailureStrategy {
        WARN_AND_SKIP,
        FAIL
    }

    /**
     * The trace log output. Defaults to {@link LoggerType#SLF4J}, which surfaces logs in Flink's
     * Web UI. {@link LoggerType#FILE} writes to per-subtask files.
     */
    public static final ConfigOption<LoggerType> TRACE_LOG_OUTPUT_TYPE =
            new ConfigOption<>("trace-log.output-type", LoggerType.class, LoggerType.SLF4J);

    /**
     * Specifies how condition evaluation failures are handled. Defaults to {@code WARN_AND_SKIP};
     * use {@code FAIL} to enforce fail-fast behavior.
     */
    public static final ConfigOption<ConditionEvaluationFailureStrategy>
            CONDITION_EVALUATION_FAILURE_STRATEGY =
                    new ConfigOption<>(
                            "action.trigger-condition.evaluate-failure-strategy",
                            ConditionEvaluationFailureStrategy.class,
                            ConditionEvaluationFailureStrategy.WARN_AND_SKIP);

    /** The directory for trace log files. Setting it selects file output. */
    public static final ConfigOption<String> TRACE_LOG_OUTPUT_BASE_DIR =
            new ConfigOption<>("trace-log.base-dir", String.class, null);

    /** Whether to pretty-print JSON in trace log files. */
    public static final ConfigOption<Boolean> TRACE_LOG_OUTPUT_PRETTY_PRINT =
            new ConfigOption<>("trace-log.pretty-print", Boolean.class, false);

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

    /**
     * The config parameter determines whether pruning sends tombstone (null-valued) records to the
     * Kafka action state topic so log compaction can reclaim pruned keys. Defaults to {@code
     * false}: disabling this option does not invalidate older restore points through pruning, but
     * the topic continues to grow. When enabled, the checkpoint whose completion triggers pruning
     * remains usable, but restoring an earlier checkpoint or savepoint may replay tombstones
     * written after that restore point, erasing action state the replay still needs and causing
     * already completed actions to re-execute. Enable only if the job never restores from earlier
     * checkpoints or savepoints, or if re-executing actions is acceptable.
     */
    public static final ConfigOption<Boolean> KAFKA_ACTION_STATE_TOMBSTONE_ENABLED =
            new ConfigOption<>("kafkaActionStateTombstoneEnabled", Boolean.class, false);

    /**
     * The separate, single-partition Kafka topic that stores committed checkpoint-aligned cleanup
     * boundaries. It must use {@code cleanup.policy=compact} without delete retention. Setting this
     * option enables boundary enforcement during recovery. It must not be the action-state data
     * topic, and it must be dedicated to one job's recovery history. The action-state data topic
     * must use {@code cleanup.policy=compact,delete}, {@code retention.ms=-1}, and {@code
     * retention.bytes=-1} so only reviewed cleanup plans advance its prefix.
     */
    public static final ConfigOption<String> KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC =
            new ConfigOption<>("kafkaActionStateCleanupControlTopic", String.class, null);

    /** The config parameter specifies the Fluss bootstrap servers. */
    public static final ConfigOption<String> FLUSS_BOOTSTRAP_SERVERS =
            new ConfigOption<>("flussBootstrapServers", String.class, "localhost:9123");

    /** The config parameter specifies the Fluss database for action state. */
    public static final ConfigOption<String> FLUSS_ACTION_STATE_DATABASE =
            new ConfigOption<>("flussActionStateDatabase", String.class, "flink_agents");

    /** The config parameter specifies the Fluss table name for action state. */
    public static final ConfigOption<String> FLUSS_ACTION_STATE_TABLE =
            new ConfigOption<>("flussActionStateTable", String.class, null);

    /** The config parameter specifies the number of buckets for the Fluss action state table. */
    public static final ConfigOption<Integer> FLUSS_ACTION_STATE_TABLE_BUCKETS =
            new ConfigOption<>("flussActionStateTableBuckets", Integer.class, 64);

    /**
     * The config parameter specifies the authentication protocol for Fluss client. Valid values:
     * {@code "PLAINTEXT"} (default, no authentication) and {@code "SASL"} (SASL/PLAIN
     * authentication). Value is case-insensitive.
     */
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

    /**
     * Trace log recording targets. Each target requires {@code scope}: either {@code EVENT_ONLY},
     * {@code ALL}, or an object with required {@code entityType} and optional {@code entityName}.
     * Names match exactly, except a suffix {@code .*}: {@code com.foo.*} matches both {@code
     * com.foo} and names beginning with {@code com.foo.}. A target's optional {@code detail} is
     * {@code OFF}, {@code STANDARD}, or {@code VERBOSE}. {@code OFF} suppresses matching records;
     * the other details affect attributes, while identities, relationships, timestamps, and
     * statuses remain complete.
     *
     * <p>Only matching entities whose effective detail is not {@code OFF} are recorded. More
     * specific scopes determine detail: exact name, longest name prefix, entity type, {@code
     * EVENT_ONLY}, then {@code ALL}. Target order does not matter. Defaults to {@code EVENT_ONLY}
     * with {@code STANDARD} detail; an explicitly empty list records no entities. A more specific
     * target can enable or disable recording even when a preset has the opposite setting.
     *
     * <p>An omitted detail inherits from a preset, not another entity target. Event targets prefer
     * {@code EVENT_ONLY} over {@code ALL}; other entity targets use {@code ALL}, falling back to
     * {@code EVENT_ONLY} when it is the only preset. Without presets, entity targets use {@code
     * STANDARD}. An {@code ALL} target with omitted detail uses {@code STANDARD}; an {@code
     * EVENT_ONLY} target with omitted detail inherits {@code ALL}, or uses {@code STANDARD} if
     * {@code ALL} is absent.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final ConfigOption<List<Map<String, Object>>> TRACE_LOG_TARGETS =
            (ConfigOption)
                    new ConfigOption<>(
                            "trace-log.targets",
                            List.class,
                            Collections.singletonList(
                                    Map.of("scope", "EVENT_ONLY", "detail", "STANDARD")));

    /**
     * The maximum string length for logged attributes at STANDARD detail. Strings exceeding this
     * length will be truncated. Defaults to 2000.
     */
    public static final ConfigOption<Integer> TRACE_LOG_MAX_STRING_LENGTH =
            new ConfigOption<>("trace-log.standard.max-string-length", Integer.class, 2000);

    /**
     * The maximum number of array elements to include in logged attributes at STANDARD detail.
     * Arrays exceeding this size will be truncated. Defaults to 20.
     */
    public static final ConfigOption<Integer> TRACE_LOG_MAX_ARRAY_ELEMENTS =
            new ConfigOption<>("trace-log.standard.max-array-elements", Integer.class, 20);

    /**
     * The maximum nesting depth for logged attributes at STANDARD detail. Objects deeper than this
     * level will be summarized. Defaults to 5.
     */
    public static final ConfigOption<Integer> TRACE_LOG_MAX_DEPTH =
            new ConfigOption<>("trace-log.standard.max-depth", Integer.class, 5);

    /** The config parameter specifies the list of event listener class names. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final ConfigOption<List<String>> EVENT_LISTENERS =
            (ConfigOption) new ConfigOption<>("event-listeners", List.class, null);
}
