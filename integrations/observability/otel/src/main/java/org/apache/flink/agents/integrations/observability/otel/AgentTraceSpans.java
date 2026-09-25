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
package org.apache.flink.agents.integrations.observability.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Assembles OpenTelemetry spans from Agent Trace Event Log records, following the mapping agreed in
 * the Agent Trace design discussions:
 *
 * <ul>
 *   <li>one input run = one Trace, with a synthesized {@code invoke_agent} root span;
 *   <li>each execution ({@code action} / {@code llm} / {@code parser} / {@code tool}) = one span,
 *       parented via {@code parentExecutionId} (falling back to the run root);
 *   <li>span and trace ids are derived deterministically from the framework ids, so re-exports are
 *       idempotent (see {@link OTelIds}).
 * </ul>
 *
 * <p>Attributes follow the OpenTelemetry GenAI semantic conventions (development stability; the
 * targeted convention set is documented per attribute below). The framework-native ids are always
 * attached under {@code flink_agents.*} so backends can correlate spans with the raw Event Log
 * regardless of semantic-convention evolution.
 */
public final class AgentTraceSpans {

    // OpenTelemetry GenAI semantic convention attributes (development stability). Keys are pinned
    // as literals on purpose: the gen_ai conventions are still evolving, and pinning makes the
    // exported schema explicit and stable per flink-agents release.
    static final AttributeKey<String> GEN_AI_OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> GEN_AI_AGENT_NAME =
            AttributeKey.stringKey("gen_ai.agent.name");
    static final AttributeKey<String> GEN_AI_TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    static final AttributeKey<String> GEN_AI_TOOL_CALL_ID =
            AttributeKey.stringKey("gen_ai.tool.call.id");
    static final AttributeKey<String> GEN_AI_TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");
    static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<String> GEN_AI_CONVERSATION_ID =
            AttributeKey.stringKey("gen_ai.conversation.id");
    static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    // Framework-native correlation attributes.
    static final AttributeKey<String> FA_INPUT_RUN_ID =
            AttributeKey.stringKey("flink_agents.input_run_id");
    static final AttributeKey<String> FA_EXECUTION_ID =
            AttributeKey.stringKey("flink_agents.execution_id");
    static final AttributeKey<String> FA_ENTITY_TYPE =
            AttributeKey.stringKey("flink_agents.entity_type");
    static final AttributeKey<String> FA_ENTITY_NAME =
            AttributeKey.stringKey("flink_agents.entity_name");
    static final AttributeKey<String> FA_EXECUTION_STATUS =
            AttributeKey.stringKey("flink_agents.execution.status");
    static final AttributeKey<Boolean> FA_EXECUTION_INCOMPLETE =
            AttributeKey.booleanKey("flink_agents.execution.incomplete");
    static final AttributeKey<String> FA_TOOL_TYPE =
            AttributeKey.stringKey("flink_agents.tool.type");

    static final String INSTRUMENTATION_SCOPE_NAME = "org.apache.flink.agents.otel";

    private final Resource resource;
    private final InstrumentationScopeInfo scope;

    public AgentTraceSpans(String serviceName) {
        this.resource =
                Resource.getDefault().toBuilder()
                        .put(AttributeKey.stringKey("service.name"), serviceName)
                        .build();
        this.scope = InstrumentationScopeInfo.create(INSTRUMENTATION_SCOPE_NAME);
    }

    /** Assembles spans from Event Log records; ordering of the input records does not matter. */
    public List<SpanData> assemble(List<TraceRecord> records) {
        return assemble(records, new ArrayList<>());
    }

    /**
     * Assembles spans and appends machine-readable {@link ConverterDiagnostic}s (incomplete
     * executions, terminal records without a start) to the given collector.
     */
    public List<SpanData> assemble(
            List<TraceRecord> records, List<ConverterDiagnostic> diagnostics) {
        // executionId -> collected lifecycle records; LinkedHashMap keeps output ordering stable.
        Map<String, ExecutionSpanBuilder> executions = new LinkedHashMap<>();
        Map<String, RunAccumulator> runs = new LinkedHashMap<>();

        for (TraceRecord record : records) {
            String eventType = record.getEventType();
            if (eventType == null
                    || !ExecutionLifecycleEvents.isExecutionLifecycleEvent(eventType)) {
                continue;
            }
            if (record.getExecutionId() == null
                    || record.getInputRunId() == null
                    || record.getTimestamp() == null) {
                continue;
            }
            executions
                    .computeIfAbsent(record.getExecutionId(), id -> new ExecutionSpanBuilder())
                    .accept(record);
            runs.computeIfAbsent(record.getInputRunId(), id -> new RunAccumulator()).accept(record);
        }

        List<SpanData> spans = new ArrayList<>(executions.size() + runs.size());
        for (Map.Entry<String, RunAccumulator> run : runs.entrySet()) {
            spans.add(buildRunRootSpan(run.getKey(), run.getValue()));
        }
        for (ExecutionSpanBuilder execution : executions.values()) {
            spans.add(buildExecutionSpan(execution, diagnostics));
        }
        return spans;
    }

    private SpanData buildRunRootSpan(String inputRunId, RunAccumulator run) {
        AttributesBuilder attributes = Attributes.builder();
        attributes.put(GEN_AI_OPERATION_NAME, "invoke_agent");
        attributes.put(FA_INPUT_RUN_ID, inputRunId);
        if (run.agentName != null) {
            attributes.put(GEN_AI_AGENT_NAME, run.agentName);
        }
        if (run.businessKey != null) {
            attributes.put(GEN_AI_CONVERSATION_ID, run.businessKey);
        }
        String name = run.agentName != null ? "invoke_agent " + run.agentName : "invoke_agent";
        return new AgentTraceSpanData(
                name,
                SpanKind.INTERNAL,
                spanContext(inputRunId, OTelIds.runRootSpanId(inputRunId)),
                SpanContext.getInvalid(),
                StatusData.unset(),
                run.minEpochNanos,
                run.maxEpochNanos,
                attributes.build(),
                resource,
                scope);
    }

    private SpanData buildExecutionSpan(
            ExecutionSpanBuilder execution, List<ConverterDiagnostic> diagnostics) {
        TraceRecord any = execution.anyRecord();
        String inputRunId = any.getInputRunId();
        String entityType = any.getEntityType();
        String entityName = any.getEntityName() != null ? any.getEntityName() : "unknown";

        AttributesBuilder attributes = Attributes.builder();
        attributes.put(FA_INPUT_RUN_ID, inputRunId);
        attributes.put(FA_EXECUTION_ID, any.getExecutionId());
        if (entityType != null) {
            attributes.put(FA_ENTITY_TYPE, entityType);
        }
        attributes.put(FA_ENTITY_NAME, entityName);
        if (any.getBusinessKey() != null) {
            attributes.put(GEN_AI_CONVERSATION_ID, any.getBusinessKey());
        }

        String name;
        SpanKind kind;
        Map<String, Object> metadata = any.getEntityMetadata();
        if (ExecutionReporter.EntityTypes.LLM.equals(entityType)) {
            // The entity name is the chat model resource; the requested model id travels in
            // entityMetadata. gen_ai.provider.name stays unset: the record does not carry it.
            String model = stringValue(metadata, LLMExecutionMetadataKeys.MODEL);
            name = model != null ? "chat " + model : "chat";
            kind = SpanKind.CLIENT;
            attributes.put(GEN_AI_OPERATION_NAME, "chat");
            if (model != null) {
                attributes.put(GEN_AI_REQUEST_MODEL, model);
            }
        } else if (ExecutionReporter.EntityTypes.TOOL.equals(entityType)) {
            name = "execute_tool " + entityName;
            // INTERNAL, as the GenAI conventions specify for execute_tool: the span measures
            // the framework running the tool, whatever transport the tool itself uses.
            kind = SpanKind.INTERNAL;
            attributes.put(GEN_AI_OPERATION_NAME, "execute_tool");
            attributes.put(GEN_AI_TOOL_NAME, entityName);
            // Prefer the provider-issued call id, which is what the model's tool-call request
            // carries; the framework-assigned id is the fallback.
            String callId = stringValue(metadata, ToolExecutionMetadataKeys.EXTERNAL_ID);
            if (callId == null) {
                callId = stringValue(metadata, ToolExecutionMetadataKeys.TOOL_CALL_ID);
            }
            if (callId != null) {
                attributes.put(GEN_AI_TOOL_CALL_ID, callId);
            }
            String toolType = stringValue(metadata, ToolExecutionMetadataKeys.TOOL_TYPE);
            if (toolType != null) {
                attributes.put(FA_TOOL_TYPE, toolType);
                String conventionType = conventionToolType(toolType);
                if (conventionType != null) {
                    attributes.put(GEN_AI_TOOL_TYPE, conventionType);
                }
            }
        } else if (ExecutionReporter.EntityTypes.ACTION.equals(entityType)) {
            name = "action " + entityName;
            kind = SpanKind.INTERNAL;
        } else if (ExecutionReporter.EntityTypes.PARSER.equals(entityType)) {
            name = "parse " + entityName;
            kind = SpanKind.INTERNAL;
            // A low-cardinality custom value: the GenAI conventions permit custom operation
            // names when no well-known value applies (parser has none).
            attributes.put(GEN_AI_OPERATION_NAME, "parse");
        } else {
            name = (entityType != null ? entityType + " " : "") + entityName;
            kind = SpanKind.INTERNAL;
        }

        StatusData status = StatusData.unset();
        TraceRecord terminal = execution.terminal;
        if (terminal != null) {
            if (ExecutionLifecycleEvents.STATUS_FAILED.equals(terminal.getStatus())) {
                status =
                        StatusData.create(
                                StatusCode.ERROR,
                                terminal.getErrorMessage() != null
                                        ? terminal.getErrorMessage()
                                        : "");
                String errorType =
                        terminal.getErrorType() != null
                                ? terminal.getErrorType()
                                : terminal.getProblemCategory();
                if (errorType != null) {
                    attributes.put(ERROR_TYPE, errorType);
                }
            }
            if (terminal.getStatus() != null) {
                attributes.put(FA_EXECUTION_STATUS, terminal.getStatus());
            }
            putUsageIfPresent(attributes, terminal.getEventAttributes());
        }
        boolean reused =
                terminal != null
                        && ExecutionLifecycleEvents.STATUS_REUSED.equals(terminal.getStatus());
        if (terminal == null) {
            // A start with no terminal: crash, a best-effort write that dropped the terminal
            // record, or recovery discarding the transient pairing — indistinguishable here, so
            // the span keeps status UNSET and carries an explicit marker instead of ERROR.
            attributes.put(FA_EXECUTION_INCOMPLETE, true);
            diagnostics.add(
                    new ConverterDiagnostic(
                            ConverterDiagnostic.INCOMPLETE_EXECUTION,
                            any.getExecutionId(),
                            "Execution has a start record but no terminal record; exported as a"
                                    + " zero-duration span with status UNSET.",
                            null));
        } else if (execution.started == null && !reused) {
            // A terminal with no start (reused executions are single-record by design).
            attributes.put(FA_EXECUTION_INCOMPLETE, true);
            diagnostics.add(
                    new ConverterDiagnostic(
                            ConverterDiagnostic.MISSING_START,
                            any.getExecutionId(),
                            "Execution has a terminal record but no start record; exported as a"
                                    + " zero-duration span at the terminal timestamp.",
                            null));
        }

        long startNanos =
                execution.started != null
                        ? epochNanos(execution.started.getTimestamp())
                        : epochNanos(terminal.getTimestamp());
        long endNanos = terminal != null ? epochNanos(terminal.getTimestamp()) : startNanos;

        SpanContext parent =
                any.getParentExecutionId() != null
                        ? spanContext(inputRunId, OTelIds.spanId(any.getParentExecutionId()))
                        : spanContext(inputRunId, OTelIds.runRootSpanId(inputRunId));

        return new AgentTraceSpanData(
                name,
                kind,
                spanContext(inputRunId, OTelIds.spanId(any.getExecutionId())),
                parent,
                status,
                startNanos,
                Math.max(endNanos, startNanos),
                attributes.build(),
                resource,
                scope);
    }

    /**
     * Maps the framework tool type onto the GenAI well-known {@code gen_ai.tool.type} values: a
     * function the agent runs itself is {@code function}; remote functions and MCP tools call out
     * to external systems, which is {@code extension}. Model built-in tools have no well-known
     * counterpart and keep only the raw {@code flink_agents.tool.type}.
     */
    private static String conventionToolType(String toolType) {
        switch (toolType) {
            case "function":
                return "function";
            case "remote_function":
            case "mcp":
                return "extension";
            default:
                return null;
        }
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void putUsageIfPresent(
            AttributesBuilder attributes, Map<String, Object> eventAttributes) {
        if (eventAttributes == null) {
            return;
        }
        Object prompt = eventAttributes.get("promptTokens");
        if (prompt instanceof Number) {
            attributes.put(GEN_AI_USAGE_INPUT_TOKENS, ((Number) prompt).longValue());
        }
        Object completion = eventAttributes.get("completionTokens");
        if (completion instanceof Number) {
            attributes.put(GEN_AI_USAGE_OUTPUT_TOKENS, ((Number) completion).longValue());
        }
    }

    private static SpanContext spanContext(String inputRunId, String spanIdHex) {
        return SpanContext.create(
                OTelIds.traceId(inputRunId),
                spanIdHex,
                TraceFlags.getSampled(),
                TraceState.getDefault());
    }

    private static long epochNanos(String isoTimestamp) {
        Instant instant = Instant.parse(isoTimestamp);
        return TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    }

    /** Started/terminal record pair for one execution id. */
    private static final class ExecutionSpanBuilder {
        private TraceRecord started;
        private TraceRecord terminal;

        void accept(TraceRecord record) {
            if (ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(
                    record.getEventType())) {
                started = record;
            } else {
                // finished / failed / reused all terminate the execution. A reused execution has
                // no started record: it becomes a point-in-time span with status "reused".
                terminal = record;
            }
        }

        TraceRecord anyRecord() {
            return started != null ? started : terminal;
        }
    }

    /** Aggregated run-level facts for the synthesized root span. */
    private static final class RunAccumulator {
        private long minEpochNanos = Long.MAX_VALUE;
        private long maxEpochNanos = Long.MIN_VALUE;
        private String agentName;
        private String businessKey;

        void accept(TraceRecord record) {
            long nanos = epochNanos(record.getTimestamp());
            minEpochNanos = Math.min(minEpochNanos, nanos);
            maxEpochNanos = Math.max(maxEpochNanos, nanos);
            if (agentName == null && record.getAgentName() != null) {
                agentName = record.getAgentName();
            }
            if (businessKey == null && record.getBusinessKey() != null) {
                businessKey = record.getBusinessKey();
            }
        }
    }
}
