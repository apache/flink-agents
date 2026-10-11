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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Condition filtering inside an internal sub-agent scope: {@link
 * InternalSubagentSetup#matchActions} applies the same event-type and condition-expression matching
 * the root router uses, evaluated against the forwarded delegate event.
 */
class InternalSubagentMatchTest {

    public static void handler(Event event, RunnerContext context) {}

    @Test
    void scopeMatchingAppliesTypeAndConditionFilters() throws Exception {
        Action typed = action("typed", InputEvent.EVENT_TYPE);
        Action conditional = action("conditional", "attributes.route == 'extra'");
        InternalSubagentSetup setup = new InternalSubagentSetup("scope", plan(typed, conditional));

        List<Action> plain = setup.matchActions(new InputEvent("plain"));
        assertThat(plain).containsExactly(typed);

        List<Action> routed =
                setup.matchActions(new Event(InputEvent.EVENT_TYPE, Map.of("route", "extra")));
        assertThat(routed).containsExactly(typed, conditional);

        List<Action> filteredOut = setup.matchActions(new Event("unrelated.type"));
        assertThat(filteredOut).isEmpty();
    }

    private static Action action(String name, String... triggerConditions) throws Exception {
        return new Action(name, function(), Arrays.asList(triggerConditions), null);
    }

    private static JavaFunction function() throws Exception {
        return new JavaFunction(
                InternalSubagentMatchTest.class.getName(),
                "handler",
                new Class[] {Event.class, RunnerContext.class});
    }

    private static AgentPlan plan(Action... actions) {
        Map<String, Action> byName = new LinkedHashMap<>();
        for (Action action : actions) {
            byName.put(action.getName(), action);
        }
        return new AgentPlan(byName);
    }
}
