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
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.api.java.functions.KeySelector;

public class JavaAgentWithPythonActionAgent extends Agent {

    public static final String PYTHON_MODULE =
            "flink_agents.e2e_tests.e2e_tests_resource_cross_language.python_action_handler";
    public static final String PYTHON_QUALNAME = "multiply_by_two";

    public JavaAgentWithPythonActionAgent() {
        addAction(
                "multiply_by_two",
                new String[] {InputEvent.EVENT_TYPE},
                new PythonFunction(PYTHON_MODULE, PYTHON_QUALNAME),
                null);
    }

    public static class SingleKeySelector implements KeySelector<Long, Long> {
        @Override
        public Long getKey(Long value) {
            return 0L;
        }
    }
}
