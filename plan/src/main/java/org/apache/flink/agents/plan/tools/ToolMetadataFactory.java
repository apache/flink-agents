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

package org.apache.flink.agents.plan.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Plan-level utility for creating ToolMetadata from annotated methods. it handles the
 * implementation logic for converting @Tool annotations into ToolMetadata objects.
 */
public class ToolMetadataFactory {

    /** Create ToolMetadata from a static method annotated with @Tool. */
    public static ToolMetadata fromStaticMethod(Method method) throws JsonProcessingException {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Only static methods are supported");
        }

        Tool toolAnnotation = method.getAnnotation(Tool.class);
        if (toolAnnotation == null) {
            throw new IllegalArgumentException("Method must be annotated with @Tool");
        }

        String name = method.getName();
        String description = toolAnnotation.description();
        String schema = SchemaUtils.generateSchema(method);

        return new ToolMetadata(name, description, schema);
    }
}
