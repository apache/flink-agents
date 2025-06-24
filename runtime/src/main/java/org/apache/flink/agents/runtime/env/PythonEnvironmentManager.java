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

import org.apache.flink.api.common.JobID;
import org.apache.flink.core.fs.Path;
import org.apache.flink.python.env.AbstractPythonEnvironmentManager;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.python.env.PythonEnvironment;
import pemja.core.PythonInterpreterConfig;

import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.python.util.PythonDependencyUtils.PARAM_DELIMITER;

/**
 * The base class of python environment manager which is used to create the PythonEnvironment
 * object. It's used to run python functions in embedded Python environment.
 */
public class PythonEnvironmentManager extends AbstractPythonEnvironmentManager {
    public PythonEnvironmentManager(
            PythonDependencyInfo dependencyInfo,
            String[] tmpDirectories,
            Map<String, String> systemEnv,
            JobID jobID) {
        super(dependencyInfo, tmpDirectories, systemEnv, jobID);
    }

    @Override
    public PythonEnvironment createEnvironment() {
        Map<String, String> env = new HashMap<>(getPythonEnv());

        PythonInterpreterConfig.PythonInterpreterConfigBuilder interpreterConfigBuilder =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .addPythonPaths(env.getOrDefault("PYTHONPATH", ""));

        // For python exec from archives, we need to config PYTHON_HOME
        if (isPythonExecFromArchives(
                dependencyInfo.getPythonExec(), dependencyInfo.getArchives())) {
            interpreterConfigBuilder
                    .setPythonHome(
                            String.join(
                                    Path.SEPARATOR,
                                    env.get(PYTHON_WORKING_DIR),
                                    dependencyInfo
                                            .getPythonExec()
                                            .substring(
                                                    0,
                                                    dependencyInfo
                                                                    .getPythonExec()
                                                                    .lastIndexOf("bin")
                                                            - 1)))
                    .setPythonExec(
                            String.join(
                                    Path.SEPARATOR,
                                    env.get(PYTHON_WORKING_DIR),
                                    dependencyInfo.getPythonExec()));
        } else {
            interpreterConfigBuilder.setPythonExec(dependencyInfo.getPythonExec());
        }

        if (env.containsKey(PYTHON_WORKING_DIR)) {
            interpreterConfigBuilder.setWorkingDirectory(env.get(PYTHON_WORKING_DIR));
        }

        return new EmbeddedPythonEnvironment(interpreterConfigBuilder.build(), env);
    }

    private static boolean isPythonExecFromArchives(
            String pythonExec, Map<String, String> archives) {
        int index = pythonExec.indexOf(Path.SEPARATOR);
        if (index == -1) {
            index = pythonExec.length();
        }

        String pythonExecBaseDir = pythonExec.substring(0, index);
        for (Map.Entry<String, String> entry : archives.entrySet()) {
            String targetDirName;
            if (entry.getValue().contains(PARAM_DELIMITER)) {
                String[] filePathAndTargetDir = entry.getValue().split(PARAM_DELIMITER, 2);
                targetDirName = filePathAndTargetDir[1];
            } else {
                targetDirName = entry.getValue();
            }

            if (targetDirName.equals(pythonExecBaseDir)) {
                return true;
            }
        }

        return false;
    }
}
