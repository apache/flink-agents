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

import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.condition.ParsedCondition;
import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;
import org.apache.flink.agents.plan.condition.ParsedCondition.TypeMatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for parsedConditions: classify, round-trip, and derived views. */
class ActionParsedConditionsTest {

    /** Reusable executor — Action's constructor only validates the signature. */
    private static Function execFn() throws Exception {
        return new JavaFunction(
                "org.apache.flink.agents.plan.actions.ActionParsedConditionsTest",
                "noopExec",
                new Class[] {
                    org.apache.flink.agents.api.Event.class,
                    org.apache.flink.agents.api.context.RunnerContext.class,
                });
    }

    /** Dummy executor referenced reflectively from {@link #execFn()}. */
    public static void noopExec(
            org.apache.flink.agents.api.Event e,
            org.apache.flink.agents.api.context.RunnerContext c) {}

    private static List<String> celSources(Action action) {
        return action.getParsedConditions().stream()
                .filter(pc -> pc instanceof CelExpression)
                .map(pc -> ((CelExpression) pc).source())
                .collect(Collectors.toList());
    }

    @Test
    void parsedConditions_singleTypeName_resolvesToTypeMatch() throws Exception {
        Action a = new Action("a", execFn(), Collections.singletonList(EventType.InputEvent));

        List<ParsedCondition> pcs = a.getParsedConditions();
        assertEquals(1, pcs.size());
        assertTrue(pcs.get(0) instanceof TypeMatch);

        TypeMatch tm = (TypeMatch) pcs.get(0);
        assertEquals(EventType.InputEvent, tm.source());
        assertEquals(EventType.InputEvent, tm.source());

        assertEquals(Collections.singletonList(EventType.InputEvent), a.getListenEventTypes());
        assertEquals(Collections.emptyList(), celSources(a));
    }

    @Test
    void parsedConditions_rawEventTypeString_isPreservedUnchanged() throws Exception {
        Action a = new Action("a", execFn(), Collections.singletonList("_input_event"));

        ParsedCondition pc = a.getParsedConditions().get(0);
        assertTrue(pc instanceof TypeMatch);
        TypeMatch tm = (TypeMatch) pc;
        assertEquals("_input_event", tm.source());
        assertEquals("_input_event", tm.source());
    }

    @Test
    void parsedConditions_celExpression_resolvesToCelExpression() throws Exception {
        Action a =
                new Action(
                        "a",
                        execFn(),
                        Collections.singletonList("type == EventType.InputEvent && id > 0"));

        List<ParsedCondition> pcs = a.getParsedConditions();
        assertEquals(1, pcs.size());
        assertTrue(pcs.get(0) instanceof CelExpression);
        assertEquals("type == EventType.InputEvent && id > 0", pcs.get(0).source());

        assertEquals(Collections.emptyList(), a.getListenEventTypes());
        assertEquals(
                Collections.singletonList("type == EventType.InputEvent && id > 0"), celSources(a));
    }

    @Test
    void parsedConditions_hasCall_isClassifiedAsCel() throws Exception {
        Action a = new Action("a", execFn(), Collections.singletonList("has(attributes.user_id)"));
        assertTrue(a.getParsedConditions().get(0) instanceof CelExpression);
    }

    @Test
    void parsedConditions_mixedList_preservesOrderAndKinds() throws Exception {
        List<String> contains =
                Arrays.asList(
                        EventType.InputEvent, // TypeMatch
                        "_chat_response_event", // TypeMatch (raw)
                        "type == 'x' && id > 0", // CelExpression
                        EventType.OutputEvent, // TypeMatch
                        "has(attributes.k)"); // CelExpression
        Action a = new Action("a", execFn(), contains);

        List<ParsedCondition> pcs = a.getParsedConditions();
        assertEquals(5, pcs.size());

        assertTrue(pcs.get(0) instanceof TypeMatch);
        assertEquals(EventType.InputEvent, ((TypeMatch) pcs.get(0)).source());

        assertTrue(pcs.get(1) instanceof TypeMatch);
        assertEquals("_chat_response_event", ((TypeMatch) pcs.get(1)).source());

        assertTrue(pcs.get(2) instanceof CelExpression);

        assertTrue(pcs.get(3) instanceof TypeMatch);
        assertEquals(EventType.OutputEvent, ((TypeMatch) pcs.get(3)).source());

        assertTrue(pcs.get(4) instanceof CelExpression);

        assertEquals(
                Arrays.asList(EventType.InputEvent, "_chat_response_event", EventType.OutputEvent),
                a.getListenEventTypes());
        assertEquals(Arrays.asList("type == 'x' && id > 0", "has(attributes.k)"), celSources(a));
    }

    @Test
    void parsedConditions_rejectsNullOrEmptyEntry() throws Exception {
        List<String> withEmpty = Arrays.asList(EventType.InputEvent, "", "type == 'x'");
        assertThrows(IllegalArgumentException.class, () -> new Action("a", execFn(), withEmpty));

        List<String> withNull = Arrays.asList(EventType.InputEvent, null, "type == 'x'");
        assertThrows(IllegalArgumentException.class, () -> new Action("a", execFn(), withNull));
    }

    @Test
    void parsedConditions_listIsUnmodifiable() throws Exception {
        Action a = new Action("a", execFn(), Collections.singletonList(EventType.InputEvent));
        List<ParsedCondition> pcs = a.getParsedConditions();
        assertNotNull(pcs);
        assertThrows(
                UnsupportedOperationException.class,
                () -> pcs.add(new CelExpression("type == 'y'")));
    }

    @Test
    void parsedConditions_hasCelCondition_isTrueWhenAnyCelEntryPresent() throws Exception {
        Action a = new Action("a", execFn(), Arrays.asList(EventType.InputEvent, "type == 'x'"));
        assertTrue(a.hasCelCondition());

        Action b = new Action("b", execFn(), Collections.singletonList(EventType.InputEvent));
        assertEquals(false, b.hasCelCondition());
    }

    @Test
    void parsedConditions_listenEventTypes_returnsTypeMatchNamesOnly() throws Exception {
        Action a =
                new Action(
                        "a",
                        execFn(),
                        Arrays.asList(EventType.InputEvent, "type == 'x'", EventType.OutputEvent));
        assertEquals(
                Arrays.asList(EventType.InputEvent, EventType.OutputEvent),
                a.getListenEventTypes());
    }
}
