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

package org.apache.flink.agents.plan.tools.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;

import java.io.IOException;

/**
 * Custom serializer for {@link FunctionTool} that handles the serialization of the metadata and
 * function.
 */
public class FunctionToolJsonSerializer extends StdSerializer<FunctionTool> {
    private static final String PYTHON_FUNC_TYPE = "PythonFunction";
    private static final String JAVA_FUNC_TYPE = "JavaFunction";

    public FunctionToolJsonSerializer() {
        super(FunctionTool.class);
    }

    @Override
    public void serialize(
            FunctionTool tool, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
            throws IOException {
        jsonGenerator.writeStartObject();

        jsonGenerator.writeObjectField("metadata", tool.getMetadata());

        jsonGenerator.writeFieldName("function");
        if (tool.getFunction() instanceof JavaFunction) {
            JavaFunction javaFunction = (JavaFunction) tool.getFunction();
            serializeJavaFunction(jsonGenerator, javaFunction);
        } else if (tool.getFunction() instanceof PythonFunction) {
            PythonFunction pythonFunction = (PythonFunction) tool.getFunction();
            serializePythonFunction(jsonGenerator, pythonFunction);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported function type: " + tool.getFunction().getClass().getName());
        }

        jsonGenerator.writeEndObject();
    }

    private void serializePythonFunction(JsonGenerator gen, PythonFunction func)
            throws IOException {
        gen.writeStartObject();
        // Mark it as a Python function
        gen.writeStringField("func_type", PYTHON_FUNC_TYPE);
        // Write the Python function details
        gen.writeStringField("module", func.getModule());
        gen.writeStringField("qualname", func.getQualName());
        gen.writeEndObject();
    }

    private void serializeJavaFunction(JsonGenerator gen, JavaFunction func) throws IOException {
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
