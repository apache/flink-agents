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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dispatch tests for plan-layer {@link Function} invocation. */
class PlanFunctionDispatchTest {

    private static int invocationCount;

    public static void handle(Event event, RunnerContext ctx) {
        invocationCount += 1;
    }

    @Test
    void javaFunctionDispatchInvokesUnderlyingMethodWithPositionalArgs() throws Exception {
        invocationCount = 0;
        JavaFunction fn =
                new JavaFunction(
                        PlanFunctionDispatchTest.class,
                        "handle",
                        new Class[] {Event.class, RunnerContext.class});

        fn.call(new InputEvent(new HashMap<>()), null);

        assertThat(invocationCount).isEqualTo(1);
    }

    @Test
    void pythonFunctionDispatchFailsWithoutInterpreter() {
        PythonFunction fn = new PythonFunction("test.module", "test_handler");

        assertThatThrownBy(() -> fn.call(new InputEvent(new HashMap<>()), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PythonFunction requires the Python interpreter");
    }

    @Test
    void pythonFunctionCheckSignatureIsLazyNoOpForAnyArity() throws Exception {
        PythonFunction fn = new PythonFunction("test.module", "test_handler");

        fn.checkSignature(new Class<?>[] {Event.class, RunnerContext.class});
        fn.checkSignature(new Class<?>[] {});
        fn.checkSignature(new Class<?>[] {Event.class});
    }
}
