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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.generateKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reproduces the cross-restart failure of the action-UUID key segment inside one JVM.
 *
 * <p>{@link JavaFunction#hashCode()} folds in {@code Arrays.hashCode(Class<?>[] parameterTypes)},
 * and {@link Class#hashCode()} is an identity hash, so the same action hashes differently in every
 * process. A fresh JVM cannot be started from a unit test, but a fresh class loader produces the
 * same effect: it defines a distinct {@link Class} object for the same bytes, with its own identity
 * hash. The loader below defines only {@link ParamEvent} itself and delegates everything else to
 * the parent, so {@link Action}'s signature check against {@link Event} still passes.
 */
public class ActionStateUtilCrossClassLoaderTest {

    private static final int MAX_PARALLELISM = 128;

    /** Subclass of {@link Event} that the isolating loader defines a second time. */
    public static class ParamEvent extends Event {
        public ParamEvent() {
            super("param");
        }
    }

    @Test
    public void testKeyIsStableWhenParameterClassesComeFromDifferentClassLoaders()
            throws Exception {
        Class<?> paramFromLoaderA = new IsolatingLoader().loadClass(ParamEvent.class.getName());
        Class<?> paramFromLoaderB = new IsolatingLoader().loadClass(ParamEvent.class.getName());
        assertNotSame(paramFromLoaderA, paramFromLoaderB);

        Action first = actionWithParameterType(paramFromLoaderA);
        Action second = actionWithParameterType(paramFromLoaderB);
        // Identity hashes almost always differ; this is the pre-fix failure mode. On the rare
        // collision there is nothing to test, so skip rather than fail.
        assumeTrue(
                first.hashCode() != second.hashCode(),
                "identity hashes of the two Class objects collided");

        InputEvent event = new InputEvent("test-input");
        assertEquals(
                generateKey("test-key", 7, first, event, MAX_PARALLELISM),
                generateKey("test-key", 7, second, event, MAX_PARALLELISM));
    }

    private static Action actionWithParameterType(Class<?> eventParameterType) throws Exception {
        return new Action(
                "stable-name",
                new JavaFunction(
                        NoOpAction.class.getName(),
                        "doNothing",
                        new Class<?>[] {eventParameterType, RunnerContext.class}),
                List.of(InputEvent.EVENT_TYPE));
    }

    /** Defines {@link ParamEvent} itself and delegates every other class to the parent loader. */
    private static final class IsolatingLoader extends ClassLoader {
        IsolatingLoader() {
            super(ActionStateUtilCrossClassLoaderTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(ParamEvent.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = readClassBytes(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private byte[] readClassBytes(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
