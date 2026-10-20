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
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Assembles OpenTelemetry spans from Trace Log records, following the mapping agreed in the Agent
 * Trace design discussions:
 *
 * <ul>
 *   <li>one input run = one Trace, with a synthesized {@code invoke_agent} root span;
 *   <li>each execution ({@code action} / {@code llm} / {@code parser} / {@code tool}) = one span,
 *       parented via {@code parentExecutionId}, or the run root when there is none or the parent
 *       has no records;
 *   <li>Event records are not spans; they are skipped;
 *   <li>span and trace ids are derived deterministically from the framework ids, so re-exports are
 *       idempotent (see {@link OTelIds}).
 * </ul>
 *
 * <p>Attributes follow the OpenTelemetry GenAI semantic conventions (development stability; the
 * targeted convention set is documented per attribute below). The framework-native ids are always
 * attached under {@code flink_agents.*} so backends can correlate spans with the raw Trace Log
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
    static final AttributeKey<String> FA_PARENT_EXECUTION_ID =
            AttributeKey.stringKey("flink_agents.parent_execution_id");
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
    static final AttributeKey<String> FA_BUSINESS_KEY =
            AttributeKey.stringKey("flink_agents.business_key");

    static final String INSTRUMENTATION_SCOPE_NAME = "org.apache.flink.agents.otel";

    private final Resource resource;
    private final InstrumentationScopeInfo scope;
    private final boolean businessKeyAsConversationId;

    public AgentTraceSpans(String serviceName) {
        this(serviceName, false);
    }

    /**
     * Creates an assembler.
     *
     * @param serviceName the {@code service.name} resource attribute
     * @param businessKeyAsConversationId whether to also report {@code businessKey} as {@code
     *     gen_ai.conversation.id}. The business key is the keyed-stream key, which is a
     *     conversation only in chat-style pipelines, so this is off by default; the key is always
     *     attached as {@code flink_agents.business_key}.
     */
    public AgentTraceSpans(String serviceName, boolean businessKeyAsConversationId) {
        this.businessKeyAsConversationId = businessKeyAsConversationId;
        this.resource =
                Resource.getDefault().toBuilder()
                        .put(AttributeKey.stringKey("service.name"), serviceName)
                        .build();
        this.scope = InstrumentationScopeInfo.create(INSTRUMENTATION_SCOPE_NAME);
    }

    /** Assembles spans from Trace Log records; ordering of the input records does not matter. */
    public List<SpanData> assemble(List<TraceRecord> records) {
        return assemble(records, new ArrayList<>());
    }

    /**
     * Assembles spans and appends machine-readable {@link ConverterDiagnostic}s (incomplete
     * executions, terminal records without a start, parents without records, records with an
     * unparseable timestamp) to the given collector.
     */
    public List<SpanData> assemble(
            List<TraceRecord> records, List<ConverterDiagnostic> diagnostics) {
        // executionId -> collected lifecycle records; LinkedHashMap keeps output ordering stable.
        Map<String, ExecutionSpanBuilder> executions = new LinkedHashMap<>();
        Map<String, RunAccumulator> runs = new LinkedHashMap<>();

        for (TraceRecord record : records) {
            TraceContext context = record.getContext();
            // Event records and execution records without a run or a status are not spans.
            if (TraceContext.EVENT_ENTITY_TYPE.equals(context.getEntityType())
                    || context.getExecutionId() == null
                    || context.getInputRunId() == null
                    || !isKnownStatus(record.getStatus())) {
                continue;
            }
            if (!isValidTimestamp(record.getTimestamp())) {
                // Skip just this record, as for an undecodable one, rather than failing the run.
                diagnostics.add(
                        new ConverterDiagnostic(
                                ConverterDiagnostic.MALFORMED_RECORD,
                                context.getExecutionId(),
                                "Invalid timestamp '"
                                        + record.getTimestamp()
                                        + "'; the record was skipped.",
                                null));
                continue;
            }
            executions
                    .computeIfAbsent(context.getExecutionId(), id -> new ExecutionSpanBuilder())
                    .accept(record);
            runs.computeIfAbsent(context.getInputRunId(), id -> new RunAccumulator())
                    .accept(record);
        }

        List<SpanData> spans = new ArrayList<>(executions.size() + runs.size());
        for (Map.Entry<String, RunAccumulator> run : runs.entrySet()) {
            spans.add(buildRunRootSpan(run.getKey(), run.getValue()));
        }
        for (ExecutionSpanBuilder execution : executions.values()) {
            spans.add(buildExecutionSpan(execution, executions.keySet(), diagnostics));
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
        putBusinessKey(attributes, run.businessKey);
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
            ExecutionSpanBuilder execution,
            Set<String> executionIds,
            List<ConverterDiagnostic> diagnostics) {
        TraceContext any = execution.anyRecord().getContext();
        String inputRunId = any.getInputRunId();
        String entityType = any.getEntityType();
        String entityName = any.getEntityName();

        AttributesBuilder attributes = Attributes.builder();
        attributes.put(FA_INPUT_RUN_ID, inputRunId);
        attributes.put(FA_EXECUTION_ID, any.getExecutionId());
        attributes.put(FA_ENTITY_TYPE, entityType);
        attributes.put(FA_ENTITY_NAME, entityName);
        putBusinessKey(attributes, any.getBusinessKey());

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
            String agentName = execution.agentName();
            if (agentName != null) {
                attributes.put(GEN_AI_AGENT_NAME, agentName);
            }
            // The call id the model's tool-call request carries.
            String callId = stringValue(metadata, ToolExecutionMetadataKeys.TOOL_CALL_ID);
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
        } else if (ExecutionReporter.EntityTypes.SUBAGENT.equals(entityType)) {
            // A sub-agent delegation the model requested as a tool call. Records don't
            // distinguish in-process agents from remote async jobs, so this keeps the
            // fallback's INTERNAL kind until they carry locality.
            name = "invoke_agent " + entityName;
            kind = SpanKind.INTERNAL;
            attributes.put(GEN_AI_OPERATION_NAME, "invoke_agent");
            attributes.put(GEN_AI_AGENT_NAME, entityName);
            String callId = stringValue(metadata, ToolExecutionMetadataKeys.TOOL_CALL_ID);
            if (callId != null) {
                attributes.put(GEN_AI_TOOL_CALL_ID, callId);
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
            name = entityType + " " + entityName;
            kind = SpanKind.INTERNAL;
        }

        StatusData status = StatusData.unset();
        TraceRecord terminal = execution.terminal;
        if (terminal != null) {
            if (TraceRecord.Statuses.FAILED.equals(terminal.getStatus())) {
                String errorMessage = attributeString(terminal, "errorMessage");
                status =
                        StatusData.create(
                                StatusCode.ERROR, errorMessage != null ? errorMessage : "");
                String errorType = attributeString(terminal, "errorType");
                if (errorType == null) {
                    errorType = terminal.getProblemCategory();
                }
                if (errorType != null) {
                    attributes.put(ERROR_TYPE, errorType);
                }
            }
            attributes.put(FA_EXECUTION_STATUS, terminal.getStatus());
            // Usage travels on the success record's metadata, which can differ from the start
            // record's metadata for the same execution.
            putUsageIfPresent(
                    attributes,
                    terminal.getContext().getEntityMetadata().get(LLMExecutionMetadataKeys.USAGE));
        }
        boolean reused =
                terminal != null && TraceRecord.Statuses.REUSED.equals(terminal.getStatus());
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
        } else if (execution.start() == null && !reused) {
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

        TraceRecord start = execution.start();
        long startNanos =
                start != null
                        ? epochNanos(start.getTimestamp())
                        : epochNanos(terminal.getTimestamp());
        long endNanos = terminal != null ? epochNanos(terminal.getTimestamp()) : startNanos;

        String parentId = any.getParentExecutionId();
        SpanContext parent;
        if (parentId != null && executionIds.contains(parentId)) {
            parent = spanContext(inputRunId, OTelIds.spanId(parentId));
        } else {
            parent = spanContext(inputRunId, OTelIds.runRootSpanId(inputRunId));
            if (parentId != null) {
                // The parent has no records, e.g. Trace Log targets that keep tool calls but not
                // Actions; attach to the run root rather than to a span that never arrives.
                attributes.put(FA_PARENT_EXECUTION_ID, parentId);
                diagnostics.add(
                        new ConverterDiagnostic(
                                ConverterDiagnostic.MISSING_PARENT,
                                any.getExecutionId(),
                                "Parent execution "
                                        + parentId
                                        + " has no records; the span was attached to the run"
                                        + " root.",
                                null));
            }
        }

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

    private void putBusinessKey(AttributesBuilder attributes, String businessKey) {
        if (businessKey == null) {
            return;
        }
        attributes.put(FA_BUSINESS_KEY, businessKey);
        if (businessKeyAsConversationId) {
            attributes.put(GEN_AI_CONVERSATION_ID, businessKey);
        }
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * A string attribute of the record. At {@code STANDARD} level the Trace Log replaces a long
     * string with {@code {"truncatedString": "<prefix>...", "omittedChars": N}}; the prefix is
     * kept.
     */
    private static String attributeString(TraceRecord record, String name) {
        Object value = record.getAttributes().get(name);
        if (value instanceof Map) {
            value = ((Map<?, ?>) value).get("truncatedString");
        }
        return value instanceof String ? (String) value : null;
    }

    private static void putUsageIfPresent(AttributesBuilder attributes, Object usage) {
        Map<?, ?> tokens = usage instanceof Map ? (Map<?, ?>) usage : Collections.emptyMap();
        Object prompt = tokens.get("promptTokens");
        if (prompt instanceof Number) {
            attributes.put(GEN_AI_USAGE_INPUT_TOKENS, ((Number) prompt).longValue());
        }
        Object completion = tokens.get("completionTokens");
        if (completion instanceof Number) {
            attributes.put(GEN_AI_USAGE_OUTPUT_TOKENS, ((Number) completion).longValue());
        }
    }

    private static boolean isKnownStatus(String status) {
        return TraceRecord.Statuses.CREATED.equals(status)
                || TraceRecord.Statuses.STARTED.equals(status)
                || TraceRecord.Statuses.SUCCESS.equals(status)
                || TraceRecord.Statuses.FAILED.equals(status)
                || TraceRecord.Statuses.REUSED.equals(status);
    }

    private static SpanContext spanContext(String inputRunId, String spanIdHex) {
        return SpanContext.create(
                OTelIds.traceId(inputRunId),
                spanIdHex,
                TraceFlags.getSampled(),
                TraceState.getDefault());
    }

    private static boolean isValidTimestamp(String isoTimestamp) {
        try {
            Instant.parse(isoTimestamp);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static long epochNanos(String isoTimestamp) {
        Instant instant = Instant.parse(isoTimestamp);
        return TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    }

    /**
     * Created/started/terminal records for one execution id. When the Trace Log repeats a record
     * (outputs are best-effort and may duplicate), the earliest one of each kind is kept, so the
     * result does not depend on record order.
     */
    private static final class ExecutionSpanBuilder {
        private TraceRecord created;
        private TraceRecord started;
        private TraceRecord terminal;

        void accept(TraceRecord record) {
            String status = record.getStatus();
            if (TraceRecord.Statuses.CREATED.equals(status)) {
                created = earlier(created, record);
            } else if (TraceRecord.Statuses.STARTED.equals(status)) {
                started = earlier(started, record);
            } else {
                // success / failed / reused all terminate the execution. A reused execution has
                // no started record: it becomes a point-in-time span with status "reused".
                terminal = earlier(terminal, record);
            }
        }

        private static TraceRecord earlier(TraceRecord current, TraceRecord candidate) {
            return current == null
                            || epochNanos(candidate.getTimestamp())
                                    < epochNanos(current.getTimestamp())
                    ? candidate
                    : current;
        }

        /**
         * Where the span starts: the started record, else the created record. A created record
         * without a started one is a Tool that was queued but never reported starting, including
         * one whose preparation failed; the created record is never a terminal.
         */
        TraceRecord start() {
            return started != null ? started : created;
        }

        TraceRecord anyRecord() {
            TraceRecord start = start();
            return start != null ? start : terminal;
        }

        /** The agent name from whichever of this execution's records carries one. */
        String agentName() {
            for (TraceRecord record : new TraceRecord[] {started, created, terminal}) {
                if (record != null && record.getContext().getAgentName() != null) {
                    return record.getContext().getAgentName();
                }
            }
            return null;
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
            TraceContext context = record.getContext();
            if (agentName == null && context.getAgentName() != null) {
                agentName = context.getAgentName();
            }
            if (businessKey == null && context.getBusinessKey() != null) {
                businessKey = context.getBusinessKey();
            }
        }
    }
}
