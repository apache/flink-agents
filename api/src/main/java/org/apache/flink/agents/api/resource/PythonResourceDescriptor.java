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
package org.apache.flink.agents.api.resource;

import java.util.HashMap;
import java.util.Map;

/** Declares a Python implementation without exposing its Java runtime wrapper. */
public final class PythonResourceDescriptor extends ResourceDescriptor {
    public PythonResourceDescriptor(String module, String clazz, Map<String, Object> arguments) {
        super(module, clazz, arguments);
        if (module == null || module.isBlank() || clazz == null || clazz.isBlank()) {
            throw new IllegalArgumentException("A Python module and class must be specified.");
        }
    }

    @Override
    public String getLanguage() {
        return "python";
    }

    /** Builder accepting the fully qualified Python implementation class. */
    public static class Builder {
        private final String module;
        private final String clazz;
        private final Map<String, Object> arguments = new HashMap<>();

        private Builder(String fullyQualifiedClass) {
            int split = fullyQualifiedClass == null ? -1 : fullyQualifiedClass.lastIndexOf('.');
            if (split <= 0 || split == fullyQualifiedClass.length() - 1) {
                throw new IllegalArgumentException("Expected a Python module.ClassName.");
            }
            module = fullyQualifiedClass.substring(0, split);
            clazz = fullyQualifiedClass.substring(split + 1);
        }

        public static Builder newBuilder(String clazz) {
            return new Builder(clazz);
        }

        public Builder addInitialArgument(String name, Object value) {
            arguments.put(name, value);
            return this;
        }

        public PythonResourceDescriptor build() {
            return new PythonResourceDescriptor(module, clazz, new HashMap<>(arguments));
        }
    }
}
