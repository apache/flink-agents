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
package org.apache.flink.agents.runtime.async;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Loads the JDK 21 continuation executor and checks retry backoff yields the mailbox thread. */
class ContinuationSuspendTest {

    @Test
    void suspendYieldsBeforeDelayElapses() throws Exception {
        if (ContinuationActionExecutor.isContinuationSupported()) {
            ContinuationSuspendDriver.run();
            return;
        }

        Path testClasses =
                Paths.get(
                        ContinuationSuspendTest.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path versionedClasses =
                testClasses.getParent().resolve("classes").resolve("META-INF/versions/21");
        Assumptions.assumeTrue(
                Files.isDirectory(versionedClasses),
                "JDK 21 continuation classes were not compiled");

        ClassLoader loader =
                new VersionedRuntimeLoader(
                        versionedClasses,
                        testClasses,
                        ContinuationSuspendTest.class.getClassLoader());
        Class<?> driver =
                loader.loadClass("org.apache.flink.agents.runtime.async.ContinuationSuspendDriver");
        try {
            driver.getDeclaredMethod("run").invoke(null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Defines async runtime classes from the multi-release output, and the suspend driver from test
     * classes, so both see the JDK 21 executor.
     */
    private static final class VersionedRuntimeLoader extends ClassLoader {
        private final Path versionedClasses;
        private final Path testClasses;

        private VersionedRuntimeLoader(
                Path versionedClasses, Path testClasses, ClassLoader parent) {
            super(parent);
            this.versionedClasses = versionedClasses;
            this.testClasses = testClasses;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (name.startsWith("org.apache.flink.agents.runtime.async.")) {
                    String relative = name.replace('.', '/') + ".class";
                    Path versioned = versionedClasses.resolve(relative);
                    Path fallback = testClasses.resolve(relative);
                    Path bytes = Files.exists(versioned) ? versioned : fallback;
                    if (Files.exists(bytes)) {
                        try {
                            byte[] content = Files.readAllBytes(bytes);
                            Class<?> defined = defineClass(name, content, 0, content.length);
                            if (resolve) {
                                resolveClass(defined);
                            }
                            return defined;
                        } catch (Exception e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
