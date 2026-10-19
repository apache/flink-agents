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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** Either an internal Agent reference or an external sub-agent descriptor. */
@JsonDeserialize(using = SubagentSpec.Deserializer.class)
public final class SubagentSpec {
    private final InternalSubagentSpec internal;
    private final DescriptorSpec external;

    private SubagentSpec(InternalSubagentSpec internal, DescriptorSpec external) {
        this.internal = internal;
        this.external = external;
    }

    public String getName() {
        return internal == null ? external.getName() : internal.getName();
    }

    public InternalSubagentSpec getInternal() {
        return internal;
    }

    public DescriptorSpec getExternal() {
        return external;
    }

    static final class Deserializer extends JsonDeserializer<SubagentSpec> {
        @Override
        public SubagentSpec deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (!node.has("clazz")) {
                for (String field : new String[] {"name", "agent"}) {
                    if (!node.path(field).isTextual()) {
                        context.reportInputMismatch(
                                InternalSubagentSpec.class,
                                "Internal sub-agent '%s' must be a string",
                                field);
                    }
                }
                if (node.hasNonNull("description") && !node.get("description").isTextual()) {
                    context.reportInputMismatch(
                            InternalSubagentSpec.class,
                            "Internal sub-agent 'description' must be a string or null");
                }
                return new SubagentSpec(
                        parser.getCodec().treeToValue(node, InternalSubagentSpec.class), null);
            }
            return new SubagentSpec(
                    null, parser.getCodec().treeToValue(node, DescriptorSpec.class));
        }
    }
}
