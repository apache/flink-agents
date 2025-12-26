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

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.utils.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test serializable of {@link Event}.
 *
 * <p>The check will be applied when send Event to Context.
 */
public class TestEventSerializable {
    /** Class which can be serialized by jackson, for it provide getter for private field. */
    public static class SerializableClass {
        private final int a;

        public SerializableClass(int a) {
            this.a = a;
        }

        public int getA() {
            return a;
        }
    }

    /**
     * Class which can't be serialized by jackson, for it doesn't provide getter for private field.
     */
    public static class UnserializableClass {
        private final int a;

        public UnserializableClass(int a) {
            this.a = a;
        }
    }

    @Test
    public void testEventInitSerializable() throws JsonProcessingException {
        InputEvent event = new InputEvent(new SerializableClass(1));
        JsonUtils.checkSerializable(event);
    }

    @Test
    public void testEventInitNonSerializable() {
        InputEvent event = new InputEvent(new UnserializableClass(1));
        Assertions.assertThrows(
                JsonProcessingException.class, () -> JsonUtils.checkSerializable(event));
    }

    @Test
    public void testEventSetAttrSerializable() throws JsonProcessingException {
        InputEvent event = new InputEvent(new SerializableClass(1));
        event.setAttr("b", new SerializableClass(1));
        JsonUtils.checkSerializable(event);
    }

    @Test
    public void testEventSetAttrNonSerializable() {
        InputEvent event = new InputEvent(new SerializableClass(1));
        event.setAttr("b", new UnserializableClass(1));
        Assertions.assertThrows(
                JsonProcessingException.class, () -> JsonUtils.checkSerializable(event));
    }
}
