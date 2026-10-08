/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.api.annotation;

import org.apache.flink.agents.api.tools.ToolParameterSource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * Whether the tool argument is required.
     *
     * @return true if the argument is required, false otherwise.
     */
    boolean required() default true;

    /**
     * The name of the tool argument.
     *
     * @return a string representing the name of the argument, which can be used to identify it in
     *     the tool's context.
     */
    String name() default "";

    /**
     * The description of the tool argument.
     *
     * @return a string describing the argument, which can be used to provide context or usage
     *     information.
     */
    String description() default "";

    /** Sentinel indicating that no default was declared. */
    String NO_DEFAULT = "\u0000";

    /**
     * The default value as JSON (including quotes for strings). Applied only when omitted.
     *
     * @return a string representing the default value of the argument.
     */
    String defaultValue() default NO_DEFAULT;

    /** Whether an explicitly supplied null is accepted. */
    boolean nullable() default false;

    /** Inclusive numeric bounds, expressed as decimal numbers. */
    String minimum() default "";

    String maximum() default "";

    /** String and array size bounds. A negative bound means unspecified. */
    int minLength() default -1;

    int maxLength() default -1;

    int minItems() default -1;

    int maxItems() default -1;

    /**
     * Whether this argument is injected by the framework at tool execution time.
     *
     * <p>Injected arguments are hidden from the model-facing tool schema.
     *
     * @return true if the argument is injected by the framework
     */
    boolean injected() default false;

    /**
     * Source used to resolve an injected argument.
     *
     * <p>Defaults to sensory memory so omitted sources read from the current request context.
     *
     * <p>Ignored unless {@link #injected()} is true.
     */
    ToolParameterSource source() default ToolParameterSource.SENSORY_MEMORY;

    /**
     * Key or path used by {@link #source()} to resolve an injected argument. When empty, the tool
     * parameter name is used.
     *
     * <p>Ignored unless {@link #injected()} is true.
     */
    String key() default "";
}
