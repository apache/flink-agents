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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract tests for {@link EventRouter}. */
class EventRouterTest {

    @Test
    void wrapToInputEventReturnsJavaInputEventForJavaInput() {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);

        Event event = router.wrapToInputEvent(42L, /* pythonActionExecutor */ null);

        assertThat(event).isInstanceOf(InputEvent.class);
        assertThat(((InputEvent) event).getInput()).isEqualTo(42L);
    }

    @Test
    void getActionsTriggeredByReturnsActionsForJavaEventClass() throws Exception {
        Action action = TestActions.noopAction();
        Map<String, Action> actions = Map.of(action.getName(), action);
        Map<String, List<Action>> byEvent = Map.of(InputEvent.class.getName(), List.of(action));
        AgentPlan plan = new AgentPlan(actions, byEvent);

        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);

        List<Action> triggered = router.getActionsTriggeredBy(new InputEvent(0L), plan);

        assertThat(triggered).containsExactly(action);
    }
}
