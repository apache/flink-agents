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

package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.condition.ActionSelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionSelectorsTest {

    public static void handler(Event event, RunnerContext context) {}

    @Test
    void preservesRawEntriesAndBuildsImmutableSelectors() throws Exception {
        List<String> rawEntries = new ArrayList<>(List.of(" a.b.c ", " score > 1 "));
        Action action = new Action("mixed", function(), rawEntries, null);
        rawEntries.clear();

        assertThat(action.getTriggerConditions()).containsExactly(" a.b.c ", " score > 1 ");
        assertThat(action.getSelectors())
                .containsExactly(
                        ActionSelector.classify("a.b.c"), ActionSelector.classify("score > 1"));
        assertThatThrownBy(() -> action.getSelectors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsInvalidSelectorContext() throws Exception {
        assertThatThrownBy(
                        () ->
                                new Action(
                                        "failing",
                                        function(),
                                        List.of("valid.event", " type == "),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trigger condition #2")
                .hasMessageContaining("action 'failing'")
                .hasMessageContaining("source \" type == \"");
    }

    private static JavaFunction function() throws Exception {
        return new JavaFunction(
                ActionSelectorsTest.class.getName(),
                "handler",
                new Class[] {Event.class, RunnerContext.class});
    }
}
