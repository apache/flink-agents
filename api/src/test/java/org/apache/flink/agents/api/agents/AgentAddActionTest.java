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

package org.apache.flink.agents.api.agents;

import org.apache.flink.agents.api.function.Function;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.api.java.tuple.Tuple3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentAddActionTest {

    public static void onInput(Object event, Object ctx) {}

    @Test
    void newFunctionOverloadStoresApiFunction() {
        Agent agent = new Agent();
        PythonFunction pf = new PythonFunction("pkg", "fn");
        agent.addAction("act", new String[] {"_input_event"}, pf, Map.of("k", "v"));

        Map<String, Tuple3<String[], Function, Map<String, Object>>> actions = agent.getActions();
        Tuple3<String[], Function, Map<String, Object>> entry = actions.get("act");
        assertThat(entry).isNotNull();
        assertThat(entry.f0).containsExactly("_input_event");
        assertThat(entry.f1).isSameAs(pf);
        assertThat(entry.f2).containsEntry("k", "v");
    }

    @Test
    void methodOverloadDelegatesToFunctionAsJavaFunction() throws Exception {
        Method m =
                AgentAddActionTest.class.getDeclaredMethod("onInput", Object.class, Object.class);
        Agent agent = new Agent();
        agent.addAction(new String[] {"_input_event"}, m);

        Tuple3<String[], Function, Map<String, Object>> entry = agent.getActions().get("onInput");
        assertThat(entry.f1).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) entry.f1;
        assertThat(jf.getQualName()).isEqualTo(AgentAddActionTest.class.getName());
        assertThat(jf.getMethodName()).isEqualTo("onInput");
    }

    @Test
    void duplicateNameRejected() {
        Agent agent = new Agent();
        agent.addAction("act", new String[] {"_input_event"}, new PythonFunction("p", "q"), null);
        assertThatThrownBy(
                        () ->
                                agent.addAction(
                                        "act",
                                        new String[] {"_input_event"},
                                        new PythonFunction("p", "q"),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("act");
    }

    @Test
    void javaFunctionDescriptorStoredAsIs() {
        Agent agent = new Agent();
        JavaFunction jf =
                new JavaFunction(
                        "com.example.Handlers",
                        "handle",
                        java.util.List.of(
                                "org.apache.flink.agents.api.Event",
                                "org.apache.flink.agents.api.context.RunnerContext"));

        agent.addAction("act", new String[] {"_input_event"}, jf, null);

        Tuple3<String[], Function, Map<String, Object>> entry = agent.getActions().get("act");
        assertThat(entry).isNotNull();
        assertThat(entry.f1).isSameAs(jf);
    }

    @Test
    void duplicateNameRejectedForJavaFunctionDescriptor() {
        Agent agent = new Agent();
        JavaFunction jf =
                new JavaFunction("com.example.X", "m", java.util.List.of("java.lang.String"));
        agent.addAction("act", new String[] {"_input_event"}, jf, null);

        assertThatThrownBy(
                        () -> agent.addAction("act", new String[] {"_input_event"}, jf, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("act");
    }

    @Test
    void addActionReturnsSelfForChaining() {
        Agent agent = new Agent();
        Agent returned =
                agent.addAction(
                        "act", new String[] {"_input_event"}, new PythonFunction("p", "q"), null);
        assertThat(returned).isSameAs(agent);
    }
}
