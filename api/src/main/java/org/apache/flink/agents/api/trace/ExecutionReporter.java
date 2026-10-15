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

import java.util.Map;

/**
 * Optional capability for reporting the creation, start, and outcome of calls made within the
 * current Action.
 *
 * <p>A reported execution is one call, such as an LLM request, parser invocation, Tool call, or
 * Subagent call. The caller allocates a unique, non-blank execution ID for each call or retry
 * attempt, and supplies that ID with every report about it, including repeated reports. The
 * creation and start helpers in {@link ExecutionReporters} can allocate this ID. Reports for one
 * call must use the same entity type and name. Metadata describes the observation and does not
 * identify the call. Each report carries only the metadata supplied for that observation. Reporting
 * does not merge metadata across reports with the same execution ID. Metadata should remain small,
 * structured, and serializable.
 *
 * <p>Reports may be missing, repeated, or delivered after the observed operation. An execution ID
 * identifies a call, not an individual report. Reporting does not deduplicate observations or
 * establish a complete lifecycle.
 *
 * <p>Implementations decide how reports are consumed or ignored.
 */
public interface ExecutionReporter {

    /** Shared entity type names for observing Actions and calls made within Actions. */
    final class EntityTypes {
        public static final String ACTION = "action";
        public static final String LLM = "llm";
        public static final String PARSER = "parser";
        public static final String TOOL = "tool";
        public static final String SUBAGENT = "subagent";

        private EntityTypes() {}
    }

    /** Shared low-cardinality failure categories for Actions and calls made within Actions. */
    final class ProblemCategories {
        public static final String ACTION_EXECUTION_FAILED = "action_execution_failed";
        public static final String MODEL_CALL_FAILED = "model_call_failed";
        public static final String MODEL_OUTPUT_PARSE_ERROR = "model_output_parse_error";
        public static final String TOOL_CALL_FAILED = "tool_call_failed";

        private ProblemCategories() {}
    }

    /**
     * Reports that a call has been created but has not necessarily started.
     *
     * <p>This optional report can describe a call prepared separately from its invocation. The
     * default implementation ignores it. A later start, success, or failure report is not
     * guaranteed; missing reports do not establish whether the call ran.
     *
     * @param executionId the unique identity allocated by the caller for this call
     * @param entityType the call type, such as LLM, parser, tool, or subagent
     * @param entityName the call target's name, such as a model or tool name
     * @param entityMetadata small structured metadata describing the observation
     */
    default void reportExecutionCreated(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata)
            throws Exception {}

    /**
     * Reports that a call made within the current Action started.
     *
     * @param executionId the unique identity allocated by the caller for this call
     * @param entityType the call type, such as LLM, parser, tool, or subagent
     * @param entityName the call target's name, such as a model or tool name
     * @param entityMetadata small structured metadata describing the observation
     */
    void reportExecutionStarted(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a call started at the supplied timestamp.
     *
     * <p>Implementations that do not retain the supplied timestamp may use their observation time.
     */
    default void reportExecutionStartedAt(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        reportExecutionStarted(executionId, entityType, entityName, entityMetadata);
    }

    /**
     * Reports that a call completed successfully.
     *
     * <p>The execution ID, entity type, and name must match any creation or start report for the
     * call.
     */
    void reportExecutionSucceeded(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a call completed successfully at the supplied timestamp.
     *
     * <p>Implementations that do not retain the supplied timestamp may use their observation time.
     */
    default void reportExecutionSucceededAt(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        reportExecutionSucceeded(executionId, entityType, entityName, entityMetadata);
    }

    /**
     * Reports that a call failed.
     *
     * <p>The execution ID, entity type, and name must match any creation or start report for the
     * call. The problem category should be a stable, low-cardinality classification.
     */
    void reportExecutionFailed(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory)
            throws Exception;

    /**
     * Reports that a call failed at the supplied timestamp.
     *
     * <p>Implementations that do not retain the supplied timestamp may use their observation time.
     */
    default void reportExecutionFailedAt(
            String executionId,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory,
            String timestamp)
            throws Exception {
        reportExecutionFailed(
                executionId, entityType, entityName, entityMetadata, error, problemCategory);
    }
}
