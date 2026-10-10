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

package org.apache.flink.agents.api.trace;

import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies the object observed by a {@link TraceRecord}, its run, and its relationships.
 *
 * <p>Field applicability is defined by TraceRecord. A context may also represent the shared run
 * scope before an object is selected for observation.
 */
@Internal
public final class TraceContext implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String EVENT_ENTITY_TYPE = "event";

    @Nullable private final String inputRunId;
    @Nullable private final String businessKey;
    @Nullable private final String agentName;
    @Nullable private final String executionId;
    @Nullable private final String parentExecutionId;
    @Nullable private final String entityType;
    @Nullable private final String entityName;
    private final Map<String, Object> entityMetadata;

    private TraceContext(
            @Nullable String inputRunId,
            @Nullable String businessKey,
            @Nullable String agentName,
            @Nullable String executionId,
            @Nullable String parentExecutionId,
            @Nullable String entityType,
            @Nullable String entityName,
            @Nullable Map<String, Object> entityMetadata) {
        this.inputRunId = inputRunId;
        this.businessKey = businessKey;
        this.agentName = agentName;
        this.executionId = executionId;
        this.parentExecutionId = parentExecutionId;
        this.entityType = entityType;
        this.entityName = entityName;
        this.entityMetadata =
                entityMetadata == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(entityMetadata);
    }

    /** Creates a run scope for one input, without identifying an Event or execution. */
    public static TraceContext forInputRun(String businessKey, @Nullable String agentName) {
        return new TraceContext(
                UUID.randomUUID().toString(), businessKey, agentName, null, null, null, null, null);
    }

    /** Creates an Action execution in the same run, linked to its triggering Event. */
    public static TraceContext forAction(
            @Nullable TraceContext sourceContext, String actionName, String triggerEventId) {
        return new TraceContext(
                sourceContext == null ? null : sourceContext.inputRunId,
                sourceContext == null ? null : sourceContext.businessKey,
                sourceContext == null ? null : sourceContext.agentName,
                UUID.randomUUID().toString(),
                null,
                ExecutionReporter.EntityTypes.ACTION,
                requireNonEmpty(actionName, "actionName"),
                Collections.singletonMap(
                        "triggerEventId", requireNonEmpty(triggerEventId, "triggerEventId")));
    }

    /**
     * Describes an Event using its existing identity and the available run and source references.
     *
     * <p>The source execution is referenced through {@code entityMetadata.producerExecutionId}.
     * Upstream references may also be supplied for framework Events without a producer execution.
     * Each observation of the same Event retains the same Event ID.
     */
    public static TraceContext forEvent(
            @Nullable TraceContext sourceContext,
            String eventType,
            String eventId,
            @Nullable String upstreamEventId,
            @Nullable String upstreamActionName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", requireNonEmpty(eventId, "eventId"));
        if (sourceContext != null && sourceContext.executionId != null) {
            metadata.put("producerExecutionId", sourceContext.executionId);
        }
        if (upstreamEventId != null) {
            metadata.put("upstreamEventId", upstreamEventId);
        }
        if (upstreamActionName != null) {
            metadata.put("upstreamActionName", upstreamActionName);
        }
        return new TraceContext(
                sourceContext == null ? null : sourceContext.inputRunId,
                sourceContext == null ? null : sourceContext.businessKey,
                sourceContext == null ? null : sourceContext.agentName,
                null,
                null,
                EVENT_ENTITY_TYPE,
                requireNonEmpty(eventType, "eventType"),
                metadata);
    }

    /** Reads record context without creating new identities. */
    static TraceContext fromExistingIds(
            @Nullable String inputRunId,
            @Nullable String businessKey,
            @Nullable String agentName,
            @Nullable String executionId,
            @Nullable String parentExecutionId,
            @Nullable String entityType,
            @Nullable String entityName,
            @Nullable Map<String, Object> entityMetadata) {
        return new TraceContext(
                inputRunId,
                businessKey,
                agentName,
                executionId,
                parentExecutionId,
                entityType,
                entityName,
                entityMetadata);
    }

    /** Creates a child execution whose parent is the current execution. */
    public TraceContext childExecution(
            String entityType, String entityName, @Nullable Map<String, Object> entityMetadata) {
        if (executionId == null || executionId.isEmpty()) {
            throw new IllegalArgumentException("A child execution must have a parent execution.");
        }
        if (EVENT_ENTITY_TYPE.equals(entityType)) {
            throw new IllegalArgumentException("An Event is not a child execution.");
        }
        return new TraceContext(
                inputRunId,
                businessKey,
                agentName,
                UUID.randomUUID().toString(),
                executionId,
                requireNonEmpty(entityType, "entityType"),
                requireNonEmpty(entityName, "entityName"),
                entityMetadata);
    }

    @Nullable
    public String getInputRunId() {
        return inputRunId;
    }

    @Nullable
    public String getBusinessKey() {
        return businessKey;
    }

    @Nullable
    public String getAgentName() {
        return agentName;
    }

    @Nullable
    public String getExecutionId() {
        return executionId;
    }

    @Nullable
    public String getParentExecutionId() {
        return parentExecutionId;
    }

    @Nullable
    public String getEntityType() {
        return entityType;
    }

    @Nullable
    public String getEntityName() {
        return entityName;
    }

    /** Returns a read-only metadata map; nested values retain their supplied representation. */
    public Map<String, Object> getEntityMetadata() {
        return Collections.unmodifiableMap(entityMetadata);
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty.");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TraceContext)) {
            return false;
        }
        TraceContext that = (TraceContext) o;
        return Objects.equals(inputRunId, that.inputRunId)
                && Objects.equals(businessKey, that.businessKey)
                && Objects.equals(agentName, that.agentName)
                && Objects.equals(executionId, that.executionId)
                && Objects.equals(parentExecutionId, that.parentExecutionId)
                && Objects.equals(entityType, that.entityType)
                && Objects.equals(entityName, that.entityName)
                && Objects.equals(entityMetadata, that.entityMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                inputRunId,
                businessKey,
                agentName,
                executionId,
                parentExecutionId,
                entityType,
                entityName,
                entityMetadata);
    }
}
