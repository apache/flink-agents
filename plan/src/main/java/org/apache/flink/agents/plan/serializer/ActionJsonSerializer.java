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

package org.apache.flink.agents.plan.serializer;

import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Custom serializer for {@link Action} that handles the serialization of the function and event
 * types.
 */
public class ActionJsonSerializer extends StdSerializer<Action> {
    private static final String PYTHON_FUNC_TYPE = "PythonFunction";
    private static final String JAVA_FUNC_TYPE = "JavaFunction";

    public ActionJsonSerializer() {
        super(Action.class);
    }

    @Override
    public void serialize(
            Action action, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
            throws IOException {
        jsonGenerator.writeStartObject();

        // Write name field
        jsonGenerator.writeStringField("name", action.getName());

        // Write exec field
        if (action.getExec() instanceof JavaFunction) {
            JavaFunction javaFunction = (JavaFunction) action.getExec();
            serializeJavaFunction(jsonGenerator, javaFunction);
        } else if (action.getExec() instanceof PythonFunction) {
            PythonFunction pythonFunction = (PythonFunction) action.getExec();
            serializePythonFunction(jsonGenerator, pythonFunction);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported function type: " + action.getExec().getClass().getName());
        }

        // Write listenEventTypes field
        jsonGenerator.writeFieldName("listen_event_types");
        jsonGenerator.writeStartArray();
        for (String eventType : action.getListenEventTypes()) {
            jsonGenerator.writeString(eventType);
        }
        jsonGenerator.writeEndArray();

        jsonGenerator.writeEndObject();
    }

    private void serializePythonFunction(JsonGenerator gen, PythonFunction func)
            throws IOException {
        gen.writeFieldName("exec");
        gen.writeStartObject();
        // Mark it as a Python function
        gen.writeStringField("func_type", PYTHON_FUNC_TYPE);
        // Write the Python function details
        gen.writeStringField("module", func.getModule());
        gen.writeStringField("qualname", func.getQualName());
        gen.writeEndObject();
    }

    private void serializeJavaFunction(JsonGenerator gen, JavaFunction func) throws IOException {
        gen.writeFieldName("exec");
        gen.writeStartObject();
        // Mark it as a Java function
        gen.writeStringField("func_type", JAVA_FUNC_TYPE);
        // Write the Java function details
        gen.writeStringField("qualname", func.getQualName());
        gen.writeStringField("method_name", func.getMethodName());
        gen.writeArrayFieldStart("parameter_types");
        for (Class<?> paramType : func.getParameterTypes()) {
            gen.writeString(paramType.getName());
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }
}
