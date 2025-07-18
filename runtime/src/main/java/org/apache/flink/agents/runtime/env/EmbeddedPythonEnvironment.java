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
package org.apache.flink.agents.runtime.env;

import org.apache.flink.python.env.PythonEnvironment;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import java.util.Map;

/** A {@link PythonEnvironment} for executing python functions in embedded python environment. */
public class EmbeddedPythonEnvironment implements PythonEnvironment {
    private final PythonInterpreterConfig config;
    private final Map<String, String> env;

    public EmbeddedPythonEnvironment(PythonInterpreterConfig config, Map<String, String> env) {
        this.config = config;
        this.env = env;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public PythonInterpreterConfig getConfig() {
        return config;
    }

    public PythonInterpreter getInterpreter() {
        return new PythonInterpreter(config);
    }
}
