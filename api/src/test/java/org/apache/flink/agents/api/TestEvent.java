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
package org.apache.flink.agents.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.InvalidParameterException;

/** Test Event. */
public class TestEvent {
    public static class SerializableClass {
        private final int a;

        public SerializableClass(int a) {
            this.a = a;
        }

        public int getA() {
            return a;
        }
    }

    public static class UnserializableClass {
        private final int a;

        public UnserializableClass(int a) {
            this.a = a;
        }
    }

    @Test
    public void testEventInitSerializable() {
        InputEvent event = new InputEvent(new SerializableClass(1));
    }

    @Test
    public void testEventInitNonSerializable() {
        Assertions.assertThrows(
                InvalidParameterException.class, () -> new InputEvent(new UnserializableClass(1)));
    }

    @Test
    public void testEventSetAttrSerializable() {
        InputEvent event = new InputEvent(new SerializableClass(1));
        event.setAttr("b", new SerializableClass(1));
    }

    @Test
    public void testEventSetAttrNonSerializable() {
        InputEvent event = new InputEvent(new SerializableClass(1));
        Assertions.assertThrows(
                InvalidParameterException.class,
                () -> event.setAttr("b", new UnserializableClass(1)));
    }
}
