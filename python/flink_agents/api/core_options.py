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


class ErrorHandlingStrategy(Enum):
    """Error handling strategy for Agent.

    Currently, only works for chat action.
    """

    RETRY = "retry"
    FAIL = "fail"
    IGNORE = "ignore"


class ShortTermMemoryTtlUpdate(Enum):
    """Update policy for short-term memory TTL."""

    ON_CREATE_AND_WRITE = "ON_CREATE_AND_WRITE"
    ON_READ_AND_WRITE = "ON_READ_AND_WRITE"


class ShortTermMemoryTtlVisibility(Enum):
    """Visibility policy for expired short-term memory state."""

    NEVER_RETURN_EXPIRED = "NEVER_RETURN_EXPIRED"
    RETURN_EXPIRED_IF_NOT_CLEANED_UP = "RETURN_EXPIRED_IF_NOT_CLEANED_UP"


class LoggerType(Enum):
    """Built-in event logger types.

    Mirrors the Java ``LoggerType`` enum so Python users can configure the
    logger type via ``AgentConfigOptions.EVENT_LOGGER_TYPE`` without using
    raw strings.
    """

    SLF4J = "slf4j"
    FILE = "file"


class EventLogLevel(Enum):
    """Log level for event logging.

    Mirrors the Java ``EventLogLevel`` enum.
    """

    OFF = "OFF"
    STANDARD = "STANDARD"
    VERBOSE = "VERBOSE"


class AgentConfigOptions:
    """CoreOptions to manage core configuration parameters for Flink Agents.

    Options are declared explicitly in Python and must stay aligned with the
    Java ``AgentConfigOptions`` class.
    """

    EVENT_LOGGER_TYPE = ConfigOption(
        key="eventLoggerType",
        config_type=LoggerType,
        default=LoggerType.SLF4J,
    )

    BASE_LOG_DIR = ConfigOption(
        key="baseLogDir",
        config_type=str,
        default=None,
    )

    PRETTY_PRINT = ConfigOption(
        key="prettyPrint",
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

    EVENT_LOG_LEVEL = ConfigOption(
        key="event-log.level",
        config_type=EventLogLevel,
        default=EventLogLevel.STANDARD,
    )

    EVENT_LOG_MAX_STRING_LENGTH = ConfigOption(
        key="event-log.standard.max-string-length",
        config_type=int,
        default=2000,
    )

    EVENT_LOG_MAX_ARRAY_ELEMENTS = ConfigOption(
        key="event-log.standard.max-array-elements",
        config_type=int,
        default=20,
    )

    EVENT_LOG_MAX_DEPTH = ConfigOption(
        key="event-log.standard.max-depth",
        config_type=int,
        default=5,
    )

    EVENT_LISTENERS = ConfigOption(
        key="event-listeners",
        config_type=list,
        default=None,
    )


class AgentExecutionOptions:
    """Execution options for Flink Agents."""

    ERROR_HANDLING_STRATEGY = ConfigOption(
        key="error-handling-strategy",
        config_type=ErrorHandlingStrategy,
        default=ErrorHandlingStrategy.FAIL,
    )

    MAX_RETRIES = ConfigOption(
        key="max-retries",
        config_type=int,
        default=3,
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

    CHAT_ASYNC = ConfigOption(
        key="chat.async",
        config_type=bool,
        default=True,
    )

    TOOL_CALL_ASYNC = ConfigOption(
        key="tool-call.async",
        config_type=bool,
        default=True,
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
