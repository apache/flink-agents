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

package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ExecutionTraceContext}. */
class ExecutionTraceContextTest {

    @Test
    void inputRunContextCarriesInputScopeWithoutExecutionEntity() {
        ExecutionTraceContext traceContext =
                ExecutionTraceContext.forInputRun("business-key-1", "review-agent");

        assertThat(traceContext.getInputRunId()).isNotBlank();
        assertThat(traceContext.getBusinessKey()).isEqualTo("business-key-1");
        assertThat(traceContext.getAgentName()).isEqualTo("review-agent");
        assertThat(traceContext.getExecutionId()).isNull();
        assertThat(traceContext.getParentExecutionId()).isNull();
        assertThat(traceContext.getEntityType()).isNull();
        assertThat(traceContext.getEntityName()).isNull();
    }

    @Test
    void childExecutionKeepsInputScopeAndUsesCurrentExecutionAsParent() {
        ExecutionTraceContext inputRunContext = ExecutionTraceContext.forInputRun("key");
        ExecutionTraceContext actionContext = inputRunContext.childExecution("action", "classify");
        ExecutionTraceContext toolContext = actionContext.childExecution("tool", "search");

        assertThat(actionContext.getInputRunId()).isEqualTo(inputRunContext.getInputRunId());
        assertThat(actionContext.getBusinessKey()).isEqualTo("key");
        assertThat(actionContext.getExecutionId()).isNotBlank();
        assertThat(actionContext.getParentExecutionId()).isNull();
        assertThat(actionContext.getEntityType()).isEqualTo("action");
        assertThat(actionContext.getEntityName()).isEqualTo("classify");

        assertThat(toolContext.getInputRunId()).isEqualTo(inputRunContext.getInputRunId());
        assertThat(toolContext.getExecutionId()).isNotBlank();
        assertThat(toolContext.getExecutionId()).isNotEqualTo(actionContext.getExecutionId());
        assertThat(toolContext.getParentExecutionId()).isEqualTo(actionContext.getExecutionId());
        assertThat(toolContext.getEntityType()).isEqualTo("tool");
        assertThat(toolContext.getEntityName()).isEqualTo("search");
    }

    @Test
    void lifecycleEventCanCarryExecutionStatusAndProblemCategory() {
        Event event =
                ExecutionLifecycleEvents.executionFailed(
                        new RuntimeException("boom"),
                        ExecutionReporter.ProblemCategories.ACTION_EXECUTION_FAILED);

        assertThat(event.getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE))
                .isEqualTo(ExecutionLifecycleEvents.STATUS_FAILED);
        assertThat(event.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE))
                .isEqualTo(ExecutionReporter.ProblemCategories.ACTION_EXECUTION_FAILED);
    }
}
