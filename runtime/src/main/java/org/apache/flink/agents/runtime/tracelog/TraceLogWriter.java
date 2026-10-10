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
package org.apache.flink.agents.runtime.tracelog;

import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLogScope;
import org.apache.flink.agents.api.logger.TraceLogger;
import org.apache.flink.agents.api.logger.TraceLoggerConfig;
import org.apache.flink.agents.api.logger.TraceLoggerFactory;
import org.apache.flink.agents.api.logger.TraceLoggerOpenParams;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.TRACE_LOG_TARGETS;

/** Selects trace records and owns the operator-scoped output logger lifecycle. */
@Internal
public final class TraceLogWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TraceLogWriter.class);

    @Nullable private final TraceLogger traceLogger;
    private final TraceLogTargetResolver targetResolver;
    private final AtomicBoolean writeFailureWarned = new AtomicBoolean();

    @Nullable private Counter writeFailuresCounter;

    public static TraceLogWriter create(AgentPlan agentPlan) {
        TraceLogTargetResolver targetResolver =
                new TraceLogTargetResolver(agentPlan.getConfig().getConfData());
        LOG.info("Trace Log configured: {}", targetResolver);
        return new TraceLogWriter(createTraceLogger(agentPlan), targetResolver);
    }

    @VisibleForTesting
    public static TraceLogWriter forTraceLogger(@Nullable TraceLogger traceLogger) {
        return forTraceLogger(traceLogger, TraceLogScope.ALL);
    }

    @VisibleForTesting
    public static TraceLogWriter forTraceLogger(
            @Nullable TraceLogger traceLogger, TraceLogScope scope) {
        return forTraceLogger(
                traceLogger, Map.of(TRACE_LOG_TARGETS.getKey(), List.of(Map.of("scope", scope))));
    }

    @VisibleForTesting
    public static TraceLogWriter forTraceLogger(
            @Nullable TraceLogger traceLogger, Map<String, Object> config) {
        return new TraceLogWriter(traceLogger, new TraceLogTargetResolver(config));
    }

    private TraceLogWriter(
            @Nullable TraceLogger traceLogger, TraceLogTargetResolver targetResolver) {
        this.traceLogger = traceLogger;
        this.targetResolver = targetResolver;
    }

    public void open(StreamingRuntimeContext runtimeContext, BuiltInMetrics builtInMetrics)
            throws Exception {
        if (traceLogger == null) {
            return;
        }
        traceLogger.open(new TraceLoggerOpenParams(runtimeContext));
        writeFailuresCounter = builtInMetrics.getTraceLogWriteFailuresCounter();
        if (traceLogger instanceof FileTraceLogger) {
            ((FileTraceLogger) traceLogger)
                    .setTruncatedRecordsCounter(
                            builtInMetrics.getTraceLogTruncatedRecordsCounter());
        } else if (traceLogger instanceof Slf4jTraceLogger) {
            ((Slf4jTraceLogger) traceLogger)
                    .setTruncatedRecordsCounter(
                            builtInMetrics.getTraceLogTruncatedRecordsCounter());
        }
    }

    /** Appends and flushes one {@link TraceRecord} best-effort. */
    public void appendAndFlush(TraceRecord record) {
        if (traceLogger == null) {
            return;
        }
        TraceLogDetail detail = targetResolver.resolve(record);
        if (detail == null) {
            return;
        }
        Exception writeError = null;
        try {
            traceLogger.append(record, detail);
        } catch (Exception appendError) {
            writeError = appendError;
        }
        try {
            traceLogger.flush();
        } catch (Exception flushError) {
            if (writeError == null) {
                writeError = flushError;
            } else if (writeError != flushError) {
                writeError.addSuppressed(flushError);
            }
        }
        if (writeError != null) {
            recordWriteFailure(writeError);
        }
    }

    private void recordWriteFailure(Exception writeError) {
        if (writeFailuresCounter != null) {
            writeFailuresCounter.inc();
        }
        if (writeFailureWarned.compareAndSet(false, true)) {
            LOG.warn(
                    "Trace Log write failed and was ignored. Subsequent failures will be logged at DEBUG.",
                    writeError);
        } else {
            LOG.debug("Trace Log write failed and was ignored.", writeError);
        }
    }

    @VisibleForTesting
    @Nullable
    public TraceLogger getTraceLogger() {
        return traceLogger;
    }

    private static TraceLogger createTraceLogger(AgentPlan agentPlan) {
        // A configured output directory selects FILE; otherwise use the configured output type.
        LoggerType loggerType = agentPlan.getConfig().get(TRACE_LOG_OUTPUT_TYPE);
        String baseLogDir = agentPlan.getConfig().get(TRACE_LOG_OUTPUT_BASE_DIR);
        if (baseLogDir != null && !baseLogDir.trim().isEmpty()) {
            loggerType = LoggerType.FILE;
        }
        // Pass output settings and attribute limits to the logger.
        TraceLoggerConfig config =
                TraceLoggerConfig.builder()
                        .loggerType(loggerType)
                        .property(
                                TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY,
                                agentPlan.getConfig().getConfData())
                        .build();
        return TraceLoggerFactory.createLogger(config);
    }

    @Override
    public void close() throws Exception {
        if (traceLogger != null) {
            traceLogger.close();
        }
    }
}
