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

    /**
     * The default value of the tool argument. If not provided, the argument is considered required.
     *
     * @return a string representing the default value of the argument.
     */
    String defaultValue() default "";
}
