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

package org.apache.flink.agents.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an agent action triggered by matching events.
 *
 * <p>Each {@link #value()} entry is an event type name string. Use the {@code EVENT_TYPE} constants
 * on built-in event classes, the {@link org.apache.flink.agents.api.EventType} constants, or plain
 * strings for custom events. Multiple entries combine with OR.
 *
 * <pre>{@code
 * // Built-in event type via the EventType constant
 * @Action(EventType.InputEvent)
 *
 * // Equivalent via the legacy class constant
 * @Action(InputEvent.EVENT_TYPE)
 *
 * // User-defined event type
 * @Action("MyCustomEvent")
 *
 * // Multiple types (OR semantics)
 * @Action({EventType.InputEvent, "MyCustomEvent"})
 * }</pre>
 *
 * <p>For a cross-language action, set {@link #target()} to a {@link PythonFunction} with a
 * non-empty {@code module}. The annotated Java body is never invoked — throw {@link
 * UnsupportedOperationException} so direct calls outside the framework fail loud:
 *
 * <pre>{@code
 * @Action(
 *     value = EventType.InputEvent,
 *     target = @PythonFunction(module = "my_pkg.handlers", qualname = "handle_input"))
 * public void handleInput(Event event, RunnerContext ctx) {
 *     throw new UnsupportedOperationException("cross-language stub");
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Action {
    /**
     * Event type name strings; multiple entries have OR semantics. Named {@code value} (not {@code
     * triggerConditions}) to enable the {@code @Action({...})} shorthand (JLS §9.7.3); corresponds
     * to Python's {@code *trigger_conditions}.
     */
    String[] value();

    /**
     * Cross-language target. When {@link PythonFunction#module()} is non-empty, dispatch routes to
     * the Python target and the annotated Java body is unused. Default (empty {@code module}) keeps
     * the action native Java.
     */
    PythonFunction target() default @PythonFunction;
}
