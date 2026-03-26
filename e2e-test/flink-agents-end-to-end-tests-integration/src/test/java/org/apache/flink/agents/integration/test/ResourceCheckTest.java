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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.resource.ResourceName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify that the Java class name defined in {@link ResourceName} corresponds to a class that
 * exists.
 */
public class ResourceCheckTest {

    private static final String JAVA_CLASS_PREFIX = "org.apache.flink.agents.";

    @Test
    public void checkResourceNameJavaClassesExist() throws Exception {
        List<String> missing = new ArrayList<>();

        collectAndCheckJavaClasses(ResourceName.class, "", false, missing);

        if (missing.isEmpty()) {
            System.out.println(
                    "Success: The Java class referenced by ResourceName has passed validation without any missing or conflicting elements.");
        }

        assertThat(missing)
                .as(
                        "The following Java class does not exist in ResourceName, please check the class name or module dependencies: %s",
                        missing)
                .isEmpty();
    }

    private void collectAndCheckJavaClasses(
            Class<?> clazz, String prefix, boolean underPythonClass, List<String> missing)
            throws Exception {
        for (Field field : clazz.getDeclaredFields()) {
            if (!isPublicStaticFinalString(field)) {
                continue;
            }
            field.setAccessible(true);
            String value = (String) field.get(null);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String fieldPath = prefix + clazz.getSimpleName() + "." + field.getName();

            // Skip Python path && unconventional path
            if (!underPythonClass && value.startsWith(JAVA_CLASS_PREFIX)) {
                try {
                    ClassLoader.getSystemClassLoader().loadClass(value);
                } catch (ClassNotFoundException e) {
                    missing.add(value + " (from " + fieldPath + ")");
                }
            }
        }

        for (Class<?> inner : clazz.getDeclaredClasses()) {
            if (!Modifier.isStatic(inner.getModifiers())) {
                continue;
            }
            boolean nextUnderPythonClass =
                    underPythonClass || "Python".equals(inner.getSimpleName());
            collectAndCheckJavaClasses(
                    inner, prefix + clazz.getSimpleName() + ".", nextUnderPythonClass, missing);
        }
    }

    private static boolean isPublicStaticFinalString(Field field) {
        int m = field.getModifiers();
        return Modifier.isPublic(m)
                && Modifier.isStatic(m)
                && Modifier.isFinal(m)
                && field.getType() == String.class;
    }
}
