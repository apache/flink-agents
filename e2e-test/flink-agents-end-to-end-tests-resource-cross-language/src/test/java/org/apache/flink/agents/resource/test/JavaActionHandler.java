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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;

/**
 * Java action referenced by the Python {@code PythonAgentWithJavaActionAgent}. Mirror of {@code
 * python_action_handler.multiply_by_two} in the Java→Python direction.
 */
public final class JavaActionHandler {

    private JavaActionHandler() {}

    public static void multiplyByTwo(Event event, RunnerContext ctx) {
        long value = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        ctx.sendEvent(new OutputEvent(value * 2));
    }
}
