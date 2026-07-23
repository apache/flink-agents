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
package org.apache.flink.agents.runtime.eventlog;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerFactory;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.BASE_LOG_DIR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LOGGER_TYPE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LOG_TRACE_ENABLED;

/** Operator-scoped writer that owns the physical Event Log logger lifecycle. */
@Internal
public final class EventLogWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventLogWriter.class);

    @Nullable private final EventLogger eventLogger;
    private final boolean traceEnabled;

    public static EventLogWriter create(AgentPlan agentPlan) {
        return new EventLogWriter(
                createEventLogger(agentPlan), agentPlan.getConfig().get(EVENT_LOG_TRACE_ENABLED));
    }

    @VisibleForTesting
    public static EventLogWriter forEventLogger(@Nullable EventLogger eventLogger) {
        return forEventLogger(eventLogger, true);
    }

    @VisibleForTesting
    public static EventLogWriter forEventLogger(
            @Nullable EventLogger eventLogger, boolean traceEnabled) {
        return new EventLogWriter(eventLogger, traceEnabled);
    }

    private EventLogWriter(@Nullable EventLogger eventLogger, boolean traceEnabled) {
        this.eventLogger = eventLogger;
        this.traceEnabled = traceEnabled;
    }

    public void open(StreamingRuntimeContext runtimeContext, BuiltInMetrics builtInMetrics)
            throws Exception {
        if (eventLogger == null) {
            return;
        }
        eventLogger.open(new EventLoggerOpenParams(runtimeContext));
        if (eventLogger instanceof FileEventLogger) {
            ((FileEventLogger) eventLogger)
                    .setTruncatedEventsCounter(builtInMetrics.getEventLogTruncatedEventsCounter());
        } else if (eventLogger instanceof Slf4jEventLogger) {
            ((Slf4jEventLogger) eventLogger)
                    .setTruncatedEventsCounter(builtInMetrics.getEventLogTruncatedEventsCounter());
        }
    }

    /** Appends and flushes a business Event best-effort. */
    public void appendBusinessEventAndFlush(
            EventContext eventContext, Event event, @Nullable ExecutionTraceContext traceContext) {
        appendAndFlush(eventContext, event, traceEnabled ? traceContext : null);
    }

    /** Appends and flushes an execution lifecycle Event when Trace recording is enabled. */
    public void appendExecutionEventAndFlush(Event event, ExecutionTraceContext traceContext) {
        if (!traceEnabled) {
            return;
        }
        appendAndFlush(new EventContext(event), event, traceContext);
    }

    private void appendAndFlush(
            EventContext eventContext, Event event, @Nullable ExecutionTraceContext traceContext) {
        if (eventLogger == null) {
            return;
        }
        try {
            eventLogger.append(eventContext, event, traceContext);
            eventLogger.flush();
        } catch (Exception logError) {
            LOG.debug("Event Log write failed and was ignored.", logError);
        }
    }

    @VisibleForTesting
    @Nullable
    public EventLogger getEventLogger() {
        return eventLogger;
    }

    private static EventLogger createEventLogger(AgentPlan agentPlan) {
        // Honor the EVENT_LOGGER_TYPE config, defaulting to SLF4J so events surface in the Flink
        // Web UI by default. An explicit baseLogDir forces the file logger for backward
        // compatibility with the existing file-based logging path.
        LoggerType loggerType = agentPlan.getConfig().get(EVENT_LOGGER_TYPE);
        String baseLogDir = agentPlan.getConfig().get(BASE_LOG_DIR);
        if (baseLogDir != null && !baseLogDir.trim().isEmpty()) {
            loggerType = LoggerType.FILE;
        }
        // The full agent config is the single source of truth for logger settings (baseLogDir,
        // prettyPrint, event-log levels, truncation limits). Each logger pulls what it needs.
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType(loggerType)
                        .property(
                                EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY,
                                agentPlan.getConfig().getConfData())
                        .build();
        return EventLoggerFactory.createLogger(config);
    }

    @Override
    public void close() throws Exception {
        if (eventLogger != null) {
            eventLogger.close();
        }
    }
}
