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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/**
 * An item under {@code agents[].actions:} — either a string reference to a shared action, or a full
 * {@link ActionSpec}.
 */
@JsonDeserialize(using = AgentActionRef.Deserializer.class)
public final class AgentActionRef {
    private final String reference;
    private final ActionSpec spec;

    private AgentActionRef(String reference, ActionSpec spec) {
        this.reference = reference;
        this.spec = spec;
    }

    public static AgentActionRef of(String reference) {
        return new AgentActionRef(reference, null);
    }

    public static AgentActionRef of(ActionSpec spec) {
        return new AgentActionRef(null, spec);
    }

    public boolean isReference() {
        return reference != null;
    }

    public String getReference() {
        return reference;
    }

    public ActionSpec getSpec() {
        return spec;
    }

    static final class Deserializer extends JsonDeserializer<AgentActionRef> {
        @Override
        public AgentActionRef deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            if (p.currentToken().isScalarValue()) {
                return AgentActionRef.of(p.getValueAsString());
            }
            ActionSpec spec = ctxt.readValue(p, ActionSpec.class);
            return AgentActionRef.of(spec);
        }
    }
}
