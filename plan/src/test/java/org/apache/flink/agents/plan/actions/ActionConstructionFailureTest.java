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

import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Pin the "silent → loud" contract: invalid CEL fails at Action construction time. */
class ActionConstructionFailureTest {

    /** Reusable executor — Action's constructor only validates the signature. */
    private static Function execFn() throws Exception {
        return new JavaFunction(
                "org.apache.flink.agents.plan.actions.ActionConstructionFailureTest",
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

    // -- Loud failure on syntactically invalid CEL --

    @Test
    void construction_throwsOnInvalidCel_trailingOperator() throws Exception {
        Function fn = execFn();
        assertThatThrownBy(() -> new Action("bad", fn, Collections.singletonList("type ==")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type ==");
    }

    @Test
    void construction_throwsOnInvalidCel_danglingPlus() throws Exception {
        Function fn = execFn();
        assertThatThrownBy(() -> new Action("bad", fn, Collections.singletonList("event.size +")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event.size +");
    }

    @Test
    void construction_throwsOnInvalidCel_unbalancedParen() throws Exception {
        Function fn = execFn();
        assertThatThrownBy(
                        () -> new Action("bad", fn, Collections.singletonList("has(attributes.k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construction_throwsOnInvalidCel_inMixedList() throws Exception {
        // The bad entry is sandwiched between two well-formed ones; the loud-failure contract
        // says we don't silently drop it just because the rest of the list is fine.
        Function fn = execFn();
        assertThatThrownBy(
                        () ->
                                new Action(
                                        "bad",
                                        fn,
                                        Arrays.asList(
                                                "InputEvent", "type ==", "has(attributes.k)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type ==");
    }

    // -- Pre-existing structural validations --

    @Test
    void construction_throwsOnEmptyContains() throws Exception {
        Function fn = execFn();
        assertThatThrownBy(() -> new Action("bad", fn, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at least one entry");
    }

    @Test
    void construction_throwsOnNullContains() throws Exception {
        Function fn = execFn();
        assertThatThrownBy(() -> new Action("bad", fn, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at least one entry");
    }

    // -- Negative control: well-formed inputs --

    @Test
    void construction_succeedsForWellFormedInputs() throws Exception {
        Function fn = execFn();
        assertDoesNotThrow(
                () ->
                        new Action(
                                "ok",
                                fn,
                                Arrays.asList(
                                        "InputEvent",
                                        "_input_event",
                                        "type == 'x' && id > 0",
                                        "has(attributes.user_id)")));
    }
}
