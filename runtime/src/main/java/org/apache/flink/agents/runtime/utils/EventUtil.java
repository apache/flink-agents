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
package org.apache.flink.agents.runtime.utils;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.runtime.python.event.PythonEvent;

/** Utilities related to the {@link Event}. */
public class EventUtil {

    public static final String PYTHON_INPUT_EVENT_NAME = "flink_agents.api.events.event.InputEvent";

    public static final String PYTHON_OUTPUT_EVENT_NAME =
            "flink_agents.api.events.event.OutputEvent";

    public static boolean isInputEvent(Event event) {
        if (event instanceof InputEvent) {
            return true;
        }
        return event instanceof PythonEvent
                && ((PythonEvent) event).getEventType().equals(PYTHON_INPUT_EVENT_NAME);
    }

    public static boolean isOutputEvent(Event event) {
        if (event instanceof OutputEvent) {
            return true;
        }
        return event instanceof PythonEvent
                && ((PythonEvent) event).getEventType().equals(PYTHON_OUTPUT_EVENT_NAME);
    }
}
