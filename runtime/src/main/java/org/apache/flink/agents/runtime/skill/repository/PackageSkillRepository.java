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

package org.apache.flink.agents.runtime.skill.repository;

import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;

import javax.annotation.Nullable;

import java.io.IOException;

/** Python package resources copied into a Java-owned directory and read locally. */
public final class PackageSkillRepository extends AbstractMaterializedSkillRepository {
    public PackageSkillRepository(
            String packageName,
            String resource,
            @Nullable PythonInterpreterManager interpreterManager)
            throws IOException {
        super(materialize(packageName, resource, interpreterManager));
    }

    private static SkillMaterializer.Materialized materialize(
            String packageName,
            String resource,
            @Nullable PythonInterpreterManager interpreterManager)
            throws IOException {
        if (interpreterManager == null) {
            throw new IOException(
                    "Python package skill source '"
                            + packageName
                            + "/"
                            + resource
                            + "' requires an initialized Python runtime bridge.");
        }
        SkillMaterializer.Materialized result = SkillMaterializer.createTempDirectory();
        try {
            try {
                interpreterManager.invoke(
                        "python_java_utils.materialize_package_skills",
                        packageName,
                        resource,
                        result.getDir().toString());
            } catch (Exception e) {
                throw new IOException(
                        "Failed to load Python package skill source '"
                                + packageName
                                + "/"
                                + resource
                                + "'. Ensure the package is installed in the task's Python environment.",
                        e);
            }
            return result;
        } catch (IOException | RuntimeException | Error e) {
            result.close();
            throw e;
        }
    }
}
