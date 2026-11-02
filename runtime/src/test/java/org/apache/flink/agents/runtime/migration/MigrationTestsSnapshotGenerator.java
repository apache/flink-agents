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
package org.apache.flink.agents.runtime.migration;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the reference snapshots of migration tests for one Flink Agents version.
 *
 * <p>Run it through the {@code generate-migration-test-data} profile from the repository root, on
 * the release branch of the version being generated. The profile runs the generator in the {@code
 * runtime} module directory, so the snapshots land in {@code runtime/src/test/resources}:
 *
 * <pre>
 * mvn clean package -Pgenerate-migration-test-data -Dgenerate.version=0.4 -DskipTests -pl runtime -am
 * </pre>
 *
 * <p>Arguments: {@code --version <version> --classes <class>[,<class>...]}.
 */
public class MigrationTestsSnapshotGenerator {

    private static final String USAGE =
            "Usage: MigrationTestsSnapshotGenerator --version <version> --classes <class>[,<class>...]";

    public static void main(String[] args) throws Throwable {
        String versionName = null;
        String classes = null;
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException(USAGE);
        }
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--version":
                    versionName = args[i + 1];
                    break;
                case "--classes":
                    classes = args[i + 1];
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        List<String> classNames = new ArrayList<>();
        if (classes != null) {
            for (String className : classes.split(",")) {
                if (!className.trim().isEmpty()) {
                    classNames.add(className.trim());
                }
            }
        }
        if (versionName == null || classNames.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }

        FlinkAgentsVersion version = FlinkAgentsVersion.parse(versionName);
        for (String className : classNames) {
            generate(Class.forName(className), version);
        }
    }

    private static void generate(Class<?> testClass, FlinkAgentsVersion version) throws Throwable {
        Constructor<?> constructor = testClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object test = constructor.newInstance();
        int generated = 0;
        for (Method method : testClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(SnapshotsGenerator.class)) {
                continue;
            }
            method.setAccessible(true);
            try {
                method.invoke(test, version);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            generated++;
        }
        if (generated == 0) {
            throw new IllegalArgumentException(
                    testClass.getName() + " has no @SnapshotsGenerator method");
        }
    }
}
