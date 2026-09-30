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
package org.apache.flink.agents.api.function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Objects;

/**
 * Pure-data descriptor for a Python callable, identified by its module and qualified name.
 *
 * <p>Carries no execution behavior — the plan-layer {@code
 * org.apache.flink.agents.plan.PythonFunction} owns invocation via the Pemja interpreter.
 */
public final class PythonFunction implements Function, Serializable {

    private static final String FIELD_MODULE = "module";
    private static final String FIELD_QUAL_NAME = "qualName";

    @JsonProperty(FIELD_MODULE)
    private final String module;

    @JsonProperty(FIELD_QUAL_NAME)
    private final String qualName;

    @JsonCreator
    public PythonFunction(
            @JsonProperty(FIELD_MODULE) String module,
            @JsonProperty(FIELD_QUAL_NAME) String qualName) {
        this.module = requireNonEmpty(module, FIELD_MODULE);
        this.qualName = requireNonEmpty(qualName, FIELD_QUAL_NAME);
    }

    /**
     * Rejects a null or empty identifier. A descriptor names a cross-language target, so an empty
     * identifier can never resolve; failing here makes every construction path ({@link #of}, {@code
     * new}, and Jackson deserialization) reject a partially specified target up front rather than
     * deferring the failure to dispatch time.
     */
    private static String requireNonEmpty(String value, String name) {
        if (Objects.requireNonNull(value, name).isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /**
     * Convenience factory for a Python cross-language target. Reads naturally in the
     * annotated-field form: {@code PythonFunction.of("my_pkg.handlers", "handle_input")}.
     */
    public static PythonFunction of(String module, String qualName) {
        return new PythonFunction(module, qualName);
    }

    public String getModule() {
        return module;
    }

    public String getQualName() {
        return qualName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PythonFunction)) return false;
        PythonFunction that = (PythonFunction) o;
        return module.equals(that.module) && qualName.equals(that.qualName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, qualName);
    }

    @Override
    public String toString() {
        return "PythonFunction{" + module + ":" + qualName + "}";
    }
}
