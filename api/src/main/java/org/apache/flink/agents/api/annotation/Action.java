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
 * Annotation for marking a method as an agent action.
 *
 * <p>This annotation specifies which event types the action should respond to. The annotated method
 * will be triggered when any of the specified event types occur.
 *
 * <p>Events are specified as type strings via {@link #listenEventTypes()}. Use the {@code
 * EVENT_TYPE} constants on built-in event classes for standard events, or plain strings for custom
 * events.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
 * public void handleInput(Event event, RunnerContext ctx) { ... }
 *
 * @Action(listenEventTypes = {InputEvent.EVENT_TYPE, "MyCustomEvent"})
 * public void handleMultiple(Event event, RunnerContext ctx) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Action {
    /**
     * List of event type strings that this action should respond to.
     *
     * @return Array of event type strings
     */
    String[] listenEventTypes();
}
