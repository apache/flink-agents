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

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runtime context used to propagate input-run and execution-lineage information. */
public final class ExecutionTraceContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String inputRunId;
    private final String businessKey;
    private final String agentName;
    private final String executionId;
    private final String parentExecutionId;
    private final String entityType;
    private final String entityName;
    private final Map<String, Object> entityMetadata;

    private ExecutionTraceContext(
            String inputRunId,
            String businessKey,
            String agentName,
            String executionId,
            String parentExecutionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata) {
        this.inputRunId = inputRunId;
        this.businessKey = businessKey;
        this.agentName = agentName;
        this.executionId = executionId;
        this.parentExecutionId = parentExecutionId;
        this.entityType = entityType;
        this.entityName = entityName;
        this.entityMetadata = copyMetadata(entityMetadata);
    }

    public static ExecutionTraceContext forInputRun(String businessKey) {
        return forInputRun(businessKey, null);
    }

    public static ExecutionTraceContext forInputRun(String businessKey, String agentName) {
        return new ExecutionTraceContext(
                UUID.randomUUID().toString(), businessKey, agentName, null, null, null, null, null);
    }

    /** Creates an execution context without Agent identity. */
    public static ExecutionTraceContext forExecution(
            String inputRunId,
            String businessKey,
            String parentExecutionId,
            String entityType,
            String entityName) {
        return new ExecutionTraceContext(
                inputRunId,
                businessKey,
                null,
                UUID.randomUUID().toString(),
                parentExecutionId,
                entityType,
                entityName,
                null);
    }

    /** Creates a sibling Action execution from the input scope carried by another trace context. */
    public static ExecutionTraceContext forAction(
            ExecutionTraceContext sourceContext, String actionName) {
        Objects.requireNonNull(sourceContext, "sourceContext");
        return new ExecutionTraceContext(
                sourceContext.inputRunId,
                sourceContext.businessKey,
                sourceContext.agentName,
                UUID.randomUUID().toString(),
                null,
                ExecutionReporter.EntityTypes.ACTION,
                actionName,
                null);
    }

    public static ExecutionTraceContext fromExistingIds(
            @Nullable String inputRunId,
            @Nullable String businessKey,
            @Nullable String agentName,
            @Nullable String executionId,
            @Nullable String parentExecutionId,
            @Nullable String entityType,
            @Nullable String entityName,
            @Nullable Map<String, Object> entityMetadata) {
        return new ExecutionTraceContext(
                inputRunId,
                businessKey,
                agentName,
                executionId,
                parentExecutionId,
                entityType,
                entityName,
                entityMetadata);
    }

    /** Creates a child execution linked to this execution through {@code parentExecutionId}. */
    public ExecutionTraceContext childExecution(String entityType, String entityName) {
        return childExecution(entityType, entityName, null);
    }

    /** Creates a child execution with entity metadata and the same containment semantics. */
    public ExecutionTraceContext childExecution(
            String entityType, String entityName, Map<String, Object> entityMetadata) {
        return new ExecutionTraceContext(
                inputRunId,
                businessKey,
                agentName,
                UUID.randomUUID().toString(),
                executionId,
                entityType,
                entityName,
                entityMetadata);
    }

    public String getInputRunId() {
        return inputRunId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getParentExecutionId() {
        return parentExecutionId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityName() {
        return entityName;
    }

    public Map<String, Object> getEntityMetadata() {
        return Collections.unmodifiableMap(entityMetadata);
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> entityMetadata) {
        if (entityMetadata == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(entityMetadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExecutionTraceContext)) {
            return false;
        }
        ExecutionTraceContext that = (ExecutionTraceContext) o;
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
