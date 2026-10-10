################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
import os
from enum import Enum

from flink_agents.api.configuration import ConfigOption


class ShortTermMemoryTtlUpdate(Enum):
    """Update policy for short-term memory TTL."""

    ON_CREATE_AND_WRITE = "ON_CREATE_AND_WRITE"
    ON_READ_AND_WRITE = "ON_READ_AND_WRITE"


class ShortTermMemoryTtlVisibility(Enum):
    """Visibility policy for expired short-term memory state."""

    NEVER_RETURN_EXPIRED = "NEVER_RETURN_EXPIRED"
    RETURN_EXPIRED_IF_NOT_CLEANED_UP = "RETURN_EXPIRED_IF_NOT_CLEANED_UP"


class LoggerType(Enum):
    """Built-in trace log output types.

    Mirrors the Java ``LoggerType`` enum so Python users can configure the
    output via ``AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE`` without using
    raw strings.
    """

    SLF4J = "slf4j"
    FILE = "file"


class ConditionEvaluationFailureStrategy(Enum):
    """Behavior when evaluating an action trigger condition fails.

    Mirrors Java ``ConditionEvaluationFailureStrategy``.
    """

    WARN_AND_SKIP = "WARN_AND_SKIP"
    FAIL = "FAIL"


class TraceLogDetail(Enum):
    """Recording detail for matched trace log records.

    OFF suppresses matching records. STANDARD and VERBOSE preserve identities,
    relationships, timestamps, and statuses in full. Mirrors the Java
    ``TraceLogDetail`` enum.
    """

    OFF = "OFF"
    STANDARD = "STANDARD"
    VERBOSE = "VERBOSE"


class TraceLogScope(Enum):
    """Preset scopes for trace log recording targets.

    Mirrors the Java ``TraceLogScope`` enum.
    """

    EVENT_ONLY = "EVENT_ONLY"
    ALL = "ALL"


class AgentConfigOptions:
    """CoreOptions to manage core configuration parameters for Flink Agents.

    Options are declared explicitly in Python and must stay aligned with the
    Java ``AgentConfigOptions`` class.
    """

    TRACE_LOG_OUTPUT_TYPE = ConfigOption(
        key="trace-log.output-type",
        config_type=LoggerType,
        default=LoggerType.SLF4J,
    )

    CONDITION_EVALUATION_FAILURE_STRATEGY = ConfigOption(
        key="action.trigger-condition.evaluate-failure-strategy",
        config_type=ConditionEvaluationFailureStrategy,
        default=ConditionEvaluationFailureStrategy.WARN_AND_SKIP,
    )

    TRACE_LOG_OUTPUT_BASE_DIR = ConfigOption(
        key="trace-log.base-dir",
        config_type=str,
        default=None,
    )

    TRACE_LOG_OUTPUT_PRETTY_PRINT = ConfigOption(
        key="trace-log.pretty-print",
        config_type=bool,
        default=False,
    )

    ACTION_STATE_STORE_BACKEND = ConfigOption(
        key="actionStateStoreBackend",
        config_type=str,
        default=None,
    )

    KAFKA_BOOTSTRAP_SERVERS = ConfigOption(
        key="kafkaBootstrapServers",
        config_type=str,
        default="localhost:9092",
    )

    KAFKA_ACTION_STATE_TOPIC = ConfigOption(
        key="kafkaActionStateTopic",
        config_type=str,
        default=None,
    )

    KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS = ConfigOption(
        key="kafkaActionStateTopicNumPartitions",
        config_type=int,
        default=64,
    )

    KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR = ConfigOption(
        key="kafkaActionStateTopicReplicationFactor",
        config_type=int,
        default=1,
    )

    KAFKA_ACTION_STATE_TOMBSTONE_ENABLED = ConfigOption(
        key="kafkaActionStateTombstoneEnabled",
        config_type=bool,
        default=False,
    )

    KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC = ConfigOption(
        key="kafkaActionStateCleanupControlTopic",
        config_type=str,
        default=None,
    )

    FLUSS_BOOTSTRAP_SERVERS = ConfigOption(
        key="flussBootstrapServers",
        config_type=str,
        default="localhost:9123",
    )

    FLUSS_ACTION_STATE_DATABASE = ConfigOption(
        key="flussActionStateDatabase",
        config_type=str,
        default="flink_agents",
    )

    FLUSS_ACTION_STATE_TABLE = ConfigOption(
        key="flussActionStateTable",
        config_type=str,
        default=None,
    )

    FLUSS_ACTION_STATE_TABLE_BUCKETS = ConfigOption(
        key="flussActionStateTableBuckets",
        config_type=int,
        default=64,
    )

    FLUSS_SECURITY_PROTOCOL = ConfigOption(
        key="flussSecurityProtocol",
        config_type=str,
        default="PLAINTEXT",
    )

    FLUSS_SASL_MECHANISM = ConfigOption(
        key="flussSaslMechanism",
        config_type=str,
        default="PLAIN",
    )

    FLUSS_SASL_JAAS_CONFIG = ConfigOption(
        key="flussSaslJaasConfig",
        config_type=str,
        default=None,
    )

    FLUSS_SASL_USERNAME = ConfigOption(
        key="flussSaslUsername",
        config_type=str,
        default=None,
    )

    FLUSS_SASL_PASSWORD = ConfigOption(
        key="flussSaslPassword",
        config_type=str,
        default=None,
    )

    JOB_IDENTIFIER = ConfigOption(
        key="job-identifier",
        config_type=str,
        default=None,
    )

    # Each target requires scope: EVENT_ONLY/ALL, or an object containing
    # entityType and optional entityName. Names match exactly, except "com.foo.*",
    # which also matches "com.foo" and its descendants. Detail is OFF (do not
    # record), STANDARD (truncate attributes), or VERBOSE (full attributes).
    # Omitted entity detail inherits a preset, never another entity target:
    # Events prefer EVENT_ONLY over ALL; other entities use ALL, or EVENT_ONLY
    # when it is the only preset. Without presets, detail defaults to STANDARD.
    # ALL defaults to STANDARD; EVENT_ONLY inherits ALL or defaults to STANDARD.
    # More specific scopes determine detail, independently of target order.
    # An empty list selects none; more specific targets can override preset OFF.
    TRACE_LOG_TARGETS = ConfigOption(
        key="trace-log.targets",
        config_type=list,
        default=[{"scope": "EVENT_ONLY", "detail": "STANDARD"}],
    )

    TRACE_LOG_MAX_STRING_LENGTH = ConfigOption(
        key="trace-log.standard.max-string-length",
        config_type=int,
        default=2000,
    )

    TRACE_LOG_MAX_ARRAY_ELEMENTS = ConfigOption(
        key="trace-log.standard.max-array-elements",
        config_type=int,
        default=20,
    )

    TRACE_LOG_MAX_DEPTH = ConfigOption(
        key="trace-log.standard.max-depth",
        config_type=int,
        default=5,
    )

    EVENT_LISTENERS = ConfigOption(
        key="event-listeners",
        config_type=list,
        default=None,
    )


class MemoryEventOptions:
    """Options controlling memory observation events.

    Per-op resolution: sub-key explicit -> master switch explicit -> built-in default
    (writes and long-term ops on; short-term/sensory reads off)
    """

    MEMORY_GENERATE_EVENT = ConfigOption(
        key="memory.generate-event", config_type=bool, default=None
    )
    SHORT_TERM_WRITE = ConfigOption(
        key="memory.generate-event.short-term-write", config_type=bool, default=None
    )
    SHORT_TERM_READ = ConfigOption(
        key="memory.generate-event.short-term-read", config_type=bool, default=None
    )
    SENSORY_WRITE = ConfigOption(
        key="memory.generate-event.sensory-write", config_type=bool, default=None
    )
    SENSORY_READ = ConfigOption(
        key="memory.generate-event.sensory-read", config_type=bool, default=None
    )
    LONG_TERM_UPDATE = ConfigOption(
        key="memory.generate-event.long-term-update", config_type=bool, default=None
    )
    LONG_TERM_GET = ConfigOption(
        key="memory.generate-event.long-term-get", config_type=bool, default=None
    )
    LONG_TERM_SEARCH = ConfigOption(
        key="memory.generate-event.long-term-search", config_type=bool, default=None
    )


class AgentExecutionOptions:
    """Execution options for Flink Agents."""

    MAX_RETRIES = ConfigOption(
        key="max-retries",
        config_type=int,
        default=0,
    )

    RETRY_WAIT_INTERVAL = ConfigOption(
        key="retry-wait-interval",
        config_type=int,
        default=1,
    )

    NUM_ASYNC_THREADS = ConfigOption(
        key="num-async-threads",
        config_type=int,
        default=os.cpu_count() * 2,
    )

    # Experimental fallback switch for the Java JDK<21 parallel execution engine.
    # Only consulted for pure-Java agents without coroutine support; plans
    # containing Python actions never use the parallel engine regardless of
    # this value.
    PARALLEL_EXECUTION_ENABLED = ConfigOption(
        key="parallel-execution.enabled",
        config_type=bool,
        default=True,
    )

    # Maximum number of input records that may be in flight concurrently. Only
    # enforced by the Java JDK<21 parallel execution engine for pure-Java
    # agents; the JDK 21 coroutine engine and plans containing Python actions
    # ignore it.
    MAX_IN_FLIGHT_INPUT_RECORDS = ConfigOption(
        key="max-in-flight-input-records",
        config_type=int,
        default=100,
    )

    CHAT_ASYNC = ConfigOption(
        key="chat.async",
        config_type=bool,
        default=True,
    )

    AGENT_RUN_BEGIN_EVENT = ConfigOption(
        key="agent-run.begin-event", config_type=bool, default=False
    )

    TOOL_CALL_ASYNC = ConfigOption(
        key="tool-call.async",
        config_type=bool,
        default=True,
    )

    # Maximum in-flight calls per ctx.gather batch, including ordinary, durable,
    # and built-in tool calls. The shared num-async-threads pool also limits actual
    # concurrency. Lower this limit to leave capacity for other calls on the subtask.
    ASYNC_BATCH_PARALLELISM = ConfigOption(
        key="async.batch.parallelism",
        config_type=int,
        default=os.cpu_count() or 1,
    )

    # Overall timeout per ctx.gather batch, in milliseconds; non-positive disables
    # it. Applies to ordinary, durable, and built-in tool calls. Running callbacks
    # may continue after timeout and occupy their worker until they return, so
    # blocking operations should also set their own timeouts.
    ASYNC_BATCH_TIMEOUT_MS = ConfigOption(
        key="async.batch.timeout.ms",
        config_type=int,
        default=-1,
    )

    RAG_ASYNC = ConfigOption(
        key="rag.async",
        config_type=bool,
        default=True,
    )

    # Set to a positive value in milliseconds to enable short-term memory TTL;
    # 0 disables it.
    SHORT_TERM_MEMORY_STATE_TTL_MS = ConfigOption(
        key="short-term-memory.state-ttl.ms",
        config_type=int,
        default=0,
    )

    # Update policy for short-term memory TTL, consulted only when TTL is enabled.
    SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE = ConfigOption(
        key="short-term-memory.state-ttl.update-type",
        config_type=ShortTermMemoryTtlUpdate,
        default=ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE,
    )

    # Visibility policy for expired short-term memory state, consulted only when TTL
    # is enabled.
    SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY = ConfigOption(
        key="short-term-memory.state-ttl.visibility",
        config_type=ShortTermMemoryTtlVisibility,
        default=ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED,
    )
