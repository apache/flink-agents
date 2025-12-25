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
package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Test Function. */
public class TestFunction {
    public static int add(int a, int b) {
        return a + b;
    }

    @Test
    public void testJavaFunctionExecution() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "add",
                        new Class[] {int.class, int.class});
        int result = (int) func.call(1, 2);
        Assertions.assertEquals(3, result);
    }

    @Test
    public void testJavaFunctionInitByClass() throws Exception {
        Function func =
                new JavaFunction(TestFunction.class, "add", new Class[] {int.class, int.class});
        int result = (int) func.call(1, 2);
        Assertions.assertEquals(3, result);
    }

    public static void check_class(InputEvent a, OutputEvent b) {}

    @Test
    public void testSignatureSameClass() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_class",
                        new Class[] {InputEvent.class, OutputEvent.class});
        func.checkSignature(new Class[] {InputEvent.class, OutputEvent.class});
    }

    @Test
    public void testSignatureSubClass() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_class",
                        new Class[] {InputEvent.class, OutputEvent.class});
        func.checkSignature(new Class[] {Event.class, Event.class});
    }

    @Test
    public void testSignatureMismatchClass() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_class",
                        new Class[] {InputEvent.class, OutputEvent.class});
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> func.checkSignature(new Class[] {OutputEvent.class, Event.class}));
    }

    @Test
    public void testSignatureMismatchArgsNum() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_class",
                        new Class[] {InputEvent.class, OutputEvent.class});
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> func.checkSignature(new Class[] {InputEvent.class}));
    }

    public static void check_primitive(int a, long b) {}

    @Test
    public void testSignatureSamePrimitive() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_primitive",
                        new Class[] {int.class, long.class});
        func.checkSignature(new Class[] {int.class, long.class});
    }

    @Test
    public void testSignatureMismatchPrimitive() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_primitive",
                        new Class[] {int.class, long.class});
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> func.checkSignature(new Class[] {int.class, int.class}));
    }

    @Test
    public void testFunctionSerializable() throws Exception {
        Function func =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestFunction",
                        "check_class",
                        new Class[] {InputEvent.class, OutputEvent.class});

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(func);
        Function serializedFunc = mapper.readValue(value, JavaFunction.class);
        Assertions.assertEquals(func, serializedFunc);
    }
}
