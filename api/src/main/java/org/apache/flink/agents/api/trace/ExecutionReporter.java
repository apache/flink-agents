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
 * Subagent call. Reports about the same call must use the same entity type, name, and metadata.
 * Metadata must distinguish calls with the same type and name that can overlap, and should remain
 * small, structured, and serializable.
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
     * @param entityType the call type, such as LLM, parser, tool, or subagent
     * @param entityName the call target's name, such as a model or tool name
     * @param entityMetadata small structured metadata shared by all reports about the call
     */
    default void reportExecutionCreated(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception {}

    /**
     * Reports that a call made within the current Action started.
     *
     * @param entityType the call type, such as LLM, parser, tool, or subagent
     * @param entityName the call target's name, such as a model or tool name
     * @param entityMetadata small structured metadata shared by all reports about the call
     */
    void reportExecutionStarted(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a call started at the supplied timestamp.
     *
     * <p>Implementations that do not retain the supplied timestamp may use their observation time.
     */
    default void reportExecutionStartedAt(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        reportExecutionStarted(entityType, entityName, entityMetadata);
    }

    /**
     * Reports that a call completed successfully.
     *
     * <p>The entity type, name, and metadata must match any creation or start report for the call.
     */
    void reportExecutionSucceeded(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a call completed successfully at the supplied timestamp.
     *
     * <p>Implementations that do not retain the supplied timestamp may use their observation time.
     */
    default void reportExecutionSucceededAt(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        reportExecutionSucceeded(entityType, entityName, entityMetadata);
    }

    /**
     * Reports that a call failed.
     *
     * <p>The entity type, name, and metadata must match any creation or start report for the call.
     * The problem category should be a stable, low-cardinality classification.
     */
    void reportExecutionFailed(
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
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory,
            String timestamp)
            throws Exception {
        reportExecutionFailed(entityType, entityName, entityMetadata, error, problemCategory);
    }
}
