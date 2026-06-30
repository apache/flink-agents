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
 * Marks a Java method as an agent action triggered by event types or condition expressions.
 *
 * <p>Each {@link #value()} entry is either an exact event-type name or a condition expression that
 * evaluates to boolean. Multiple entries use OR semantics. An entry matching the event-type syntax
 * is classified as an event type rather than as a condition expression. To combine an event-type
 * restriction and an attribute predicate using AND, place both in a single expression, for example
 * {@code type == EventType.InputEvent && score > 5}.
 *
 * <p>The event-type syntax is a bare or dotted identifier, such as {@code order.created}. Boolean
 * attribute conditions must therefore be explicit, for example {@code ready == true}. Event types
 * containing characters not supported by the bare form, such as hyphens, can use the quoted
 * compatibility form, such as {@code 'order-created'}.
 *
 * <p>The API preserves entries as raw strings. Entries are classified and condition expressions are
 * validated when the agent plan is built.
 *
 * <p>Set {@link #target()} to a {@link PythonFunction} configured with a module for a
 * cross-language action. The annotated Java method is not invoked for such actions.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Action {
    /**
     * Raw event-type names or explicit Boolean conditions combined with OR semantics. Named {@code
     * value} to enable the {@code @Action({...})} shorthand (JLS §9.7.3); corresponds to Python's
     * {@code *trigger_conditions}.
     */
    String[] value();

    /**
     * Cross-language target. When {@link PythonFunction#module()} is non-empty, dispatch routes to
     * the Python target and the annotated Java body is unused. Default (empty {@code module}) keeps
     * the action native Java.
     */
    PythonFunction target() default @PythonFunction;
}
