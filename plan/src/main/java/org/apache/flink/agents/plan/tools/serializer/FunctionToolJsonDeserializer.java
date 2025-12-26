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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;

import java.io.IOException;

/**
 * Custom deserializer for {@link FunctionTool} that handles the deserialization of the metadata and
 * function.
 */
public class FunctionToolJsonDeserializer extends StdDeserializer<FunctionTool> {

    public FunctionToolJsonDeserializer() {
        super(FunctionTool.class);
    }

    @Override
    public FunctionTool deserialize(
            JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException {
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        ToolMetadata metadata =
                new ObjectMapper().treeToValue(node.get("metadata"), ToolMetadata.class);

        // Deserialize the function based on its type
        JsonNode functionNode = node.get("function");
        String funcType = functionNode.get("func_type").asText();
        Function func;
        if (JavaFunction.class.getSimpleName().equals(funcType)) {
            func = deserializeJavaFunction(functionNode);
        } else if (PythonFunction.class.getSimpleName().equals(funcType)) {
            func = deserializePythonFunction(functionNode);
        } else {
            throw new IOException("Unsupported function type: " + funcType);
        }

        return new FunctionTool(metadata, func);
    }

    private PythonFunction deserializePythonFunction(JsonNode execNode) {
        String module = execNode.get("module").asText();
        String qualName = execNode.get("qualname").asText();
        return new PythonFunction(module, qualName);
    }

    private JavaFunction deserializeJavaFunction(JsonNode execNode) throws IOException {
        String qualName = execNode.get("qualname").asText();
        String methodName = execNode.get("method_name").asText();
        Class<?>[] parameterTypes = new Class<?>[execNode.get("parameter_types").size()];
        for (int i = 0; i < parameterTypes.length; i++) {
            try {
                String parameterTypeName = execNode.get("parameter_types").get(i).asText();
                parameterTypes[i] = Class.forName(parameterTypeName);
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize parameter type", e);
            }
        }
        try {
            return new JavaFunction(qualName, methodName, parameterTypes);
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to create JavaFunction with qualName \"%s\" and method name \"%s\"",
                            qualName, methodName),
                    e);
        }
    }
}
