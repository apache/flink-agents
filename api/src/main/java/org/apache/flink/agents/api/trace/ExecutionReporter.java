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
 * Optional capability for reporting logical executions nested inside the current action.
 *
 * <p>Implementations decide how reports are consumed or ignored. Callers should provide stable
 * entity type/name pairs and keep metadata small, structured, serializable, and stable for equality
 * matching between the start and terminal reports of the same logical execution.
 */
public interface ExecutionReporter {

    /** Shared entity type names for execution reports. */
    final class EntityTypes {
        public static final String ACTION = "action";
        public static final String LLM = "llm";
        public static final String PARSER = "parser";
        public static final String TOOL = "tool";

        private EntityTypes() {}
    }

    /** Shared low-cardinality problem categories for failed execution reports. */
    final class ProblemCategories {
        public static final String ACTION_EXECUTION_FAILED = "action_execution_failed";
        public static final String MODEL_CALL_FAILED = "model_call_failed";
        public static final String MODEL_OUTPUT_PARSE_ERROR = "model_output_parse_error";
        public static final String TOOL_CALL_FAILED = "tool_call_failed";

        private ProblemCategories() {}
    }

    /**
     * Reports that a logical execution started within the current action.
     *
     * @param entityType stable category of the reported execution, such as LLM, parser, or tool
     * @param entityName stable name of the reported execution, such as model or tool name
     * @param entityMetadata small structured metadata used to distinguish repeated executions with
     *     the same type/name
     */
    void reportExecutionStarted(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a previously started logical execution completed successfully.
     *
     * <p>The entity type/name/metadata should match the corresponding start report when one was
     * reported.
     */
    void reportExecutionSucceeded(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception;

    /**
     * Reports that a logical execution failed.
     *
     * <p>The entity type/name/metadata should match the corresponding start report when one was
     * reported. The problem category should be a stable, low-cardinality classification.
     */
    void reportExecutionFailed(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory)
            throws Exception;
}
