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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for Event flow and execution relationships in {@link TraceContext}. */
class TraceContextTest {

    @Test
    void eventAndActionContextsDescribeTheirOwnEntitiesWithinOneRun() {
        TraceContext run = TraceContext.forInputRun("order-1", "order-agent");
        TraceContext input = TraceContext.forEvent(run, "InputEvent", "event-1", null, null);
        TraceContext action = TraceContext.forAction(input, "create_order", "event-1");
        TraceContext output =
                TraceContext.forEvent(action, "OrderCreated", "event-2", "event-1", "create_order");
        TraceContext nextAction = TraceContext.forAction(output, "ship_order", "event-2");

        assertThat(input.getInputRunId()).isEqualTo(run.getInputRunId());
        assertThat(input.getEntityMetadata()).containsOnlyKeys("eventId");
        assertThat(input.getExecutionId()).isNull();
        assertThat(action.getEntityMetadata()).containsEntry("triggerEventId", "event-1");
        assertThat(output.getEntityType()).isEqualTo("event");
        assertThat(output.getEntityName()).isEqualTo("OrderCreated");
        assertThat(output.getExecutionId()).isNull();
        assertThat(output.getParentExecutionId()).isNull();
        assertThat(output.getBusinessKey()).isEqualTo("order-1");
        assertThat(output.getAgentName()).isEqualTo("order-agent");
        assertThat(output.getEntityMetadata())
                .containsEntry("eventId", "event-2")
                .containsEntry("producerExecutionId", action.getExecutionId())
                .containsEntry("upstreamEventId", "event-1")
                .containsEntry("upstreamActionName", "create_order")
                .doesNotContainKey("triggerEventId");
        assertThat(nextAction.getInputRunId()).isEqualTo(run.getInputRunId());
        assertThat(nextAction.getExecutionId()).isNotEqualTo(action.getExecutionId());
        assertThat(nextAction.getParentExecutionId()).isNull();
        assertThat(nextAction.getEntityMetadata()).containsEntry("triggerEventId", "event-2");
    }

    @Test
    void childExecutionsKeepContainmentSeparateFromEventFlow() {
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("key", "agent"), "classify", "input");
        TraceContext tool = action.childExecution("tool", "search", Map.of("toolCallId", "call-1"));

        assertThat(tool.getInputRunId()).isEqualTo(action.getInputRunId());
        assertThat(tool.getAgentName()).isEqualTo("agent");
        assertThat(tool.getExecutionId()).isNotEqualTo(action.getExecutionId());
        assertThat(tool.getParentExecutionId()).isEqualTo(action.getExecutionId());
        assertThat(tool.getEntityMetadata())
                .containsExactlyEntriesOf(Map.of("toolCallId", "call-1"));
        assertThatThrownBy(() -> action.childExecution("event", "OrderCreated", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separateInputsForTheSameBusinessKeyHaveSeparateRuns() {
        TraceContext first = TraceContext.forInputRun("key", "agent");
        TraceContext second = TraceContext.forInputRun("key", "agent");

        assertThat(second.getInputRunId()).isNotEqualTo(first.getInputRunId());
        assertThat(first.getExecutionId()).isNull();
        assertThat(first.getEntityType()).isNull();
    }

    @Test
    void childExecutionRequiresAnExecutionParent() {
        TraceContext run = TraceContext.forInputRun("key", "agent");
        TraceContext event = TraceContext.forEvent(run, "InputEvent", "input", null, null);

        for (TraceContext context : List.of(run, event)) {
            assertThatThrownBy(() -> context.childExecution("tool", "search", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent execution");
        }
    }

    @Test
    void frameworkEventCanHaveSourceReferencesWithoutAProducingExecution() {
        TraceContext context =
                TraceContext.forEvent(
                        TraceContext.forInputRun("key", null), "RunBegin", "begin", "input", null);

        assertThat(context.getEntityMetadata())
                .containsEntry("upstreamEventId", "input")
                .doesNotContainKey("producerExecutionId")
                .doesNotContainKey("upstreamActionName");
        TraceContext unscoped = TraceContext.forEvent(null, "CustomEvent", "event", null, null);
        assertThat(unscoped.getInputRunId()).isNull();
        assertThat(unscoped.getEntityMetadata())
                .containsExactlyEntriesOf(Map.of("eventId", "event"));
    }

    @Test
    void restoredContextKeepsIdsAndCopiesItsMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("triggerEventId", "event-1");
        TraceContext context =
                TraceContext.fromExistingIds(
                        "run",
                        "key",
                        "agent",
                        "execution",
                        null,
                        "action",
                        "create_order",
                        metadata);
        metadata.put("triggerEventId", "changed");

        assertThat(context.getExecutionId()).isEqualTo("execution");
        assertThat(context.getEntityMetadata()).containsEntry("triggerEventId", "event-1");
        assertThatThrownBy(() -> context.getEntityMetadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
