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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.api.common.JobID;
import pemja.core.PythonInterpreter;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/** Coordinates Flink Python dependency generations with Pemja's shared import state. */
final class PythonDependencyGenerationManager {

    private static final String PYTHON_IMPORT =
            "from flink_agents.runtime import _python_dependency";
    private static final String ENSURE_PYTHON_DEPENDENCY_GENERATION =
            "_python_dependency.ensure_python_dependency_generation";

    private PythonDependencyGenerationManager() {}

    static boolean ensurePythonDependencyGeneration(
            PythonInterpreter interpreter, JobID jobId, String generation) {
        checkNotNull(interpreter);
        checkNotNull(jobId);
        checkNotNull(generation);

        interpreter.exec(PYTHON_IMPORT);
        Object result =
                interpreter.invoke(
                        ENSURE_PYTHON_DEPENDENCY_GENERATION, jobId.toHexString(), generation);
        checkState(
                result instanceof Boolean,
                "Python dependency generation guard returned an invalid result: %s",
                result);
        return (Boolean) result;
    }
}
