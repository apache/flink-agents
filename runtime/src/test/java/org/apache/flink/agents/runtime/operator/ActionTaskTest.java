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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for output finalization owned by {@link ActionTask}. */
class ActionTaskTest {

    @Test
    void resultFinalizesOutputLineage() {
        Event triggeringEvent = new InputEvent(1L);
        Action action = TestActions.noopAction();
        ActionTask task = new JavaActionTask("key", triggeringEvent, action);
        Event outputEvent = new Event("result");

        ActionTask.ActionTaskResult result =
                task.new ActionTaskResult(true, List.of(outputEvent), null);

        assertThat(result.getOutputEvents()).containsExactly(outputEvent);
        assertThat(outputEvent.getUpstreamEventId()).isEqualTo(triggeringEvent.getId());
        assertThat(outputEvent.getUpstreamActionName()).isEqualTo(action.getName());
    }

    @Test
    void resultRejectsSelfLoopBeforeMutatingAnyOutput() {
        Event triggeringEvent = new InputEvent(1L);
        Action action = TestActions.noopAction();
        ActionTask task = new JavaActionTask("key", triggeringEvent, action);
        Event validOutput = new Event("result");

        assertThatThrownBy(
                        () ->
                                task
                                .new ActionTaskResult(
                                        true, List.of(validOutput, triggeringEvent), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(action.getName())
                .hasMessageContaining(triggeringEvent.getId().toString());

        assertThat(validOutput.getUpstreamEventId()).isNull();
        assertThat(validOutput.getUpstreamActionName()).isNull();
        assertThat(triggeringEvent.getUpstreamEventId()).isNull();
        assertThat(triggeringEvent.getUpstreamActionName()).isNull();
    }
}
