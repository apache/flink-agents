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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAttachmentUtilsTest {

    private MemoryObject sensoryMemory;
    private RunnerContext context;

    @BeforeEach
    void setUp() throws Exception {
        sensoryMemory =
                new MemoryObjectImpl(
                        MemoryObject.MemoryType.SENSORY,
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        MemoryObjectImpl.ROOT_KEY,
                        new LinkedList<>());
        context = new MockRunnerContext(sensoryMemory);
    }

    @Test
    void storesEventAttachments() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("value", "original");
        Event event =
                new Event(
                        eventId,
                        "AttachmentStep",
                        Map.of(),
                        new HashMap<>(Map.of("payload", payload)));

        EventAttachmentUtils.storeEventAttachments(event, context);

        Object attachment = event.getAttachment("payload");
        assertTrue(attachment instanceof MemoryRef);
        MemoryRef reference = (MemoryRef) attachment;
        assertEquals(
                EventAttachmentUtils.buildAttachmentPath(eventId, "payload"), reference.getPath());
        assertEquals(payload, sensoryMemory.get(reference).getValue());
    }

    @Test
    void storesAttachmentsFromImmutableMap() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("value", "original");
        Map<String, Object> attachments = Map.of("payload", payload);
        Event event = new Event(eventId, "AttachmentStep", Map.of(), attachments);

        EventAttachmentUtils.storeEventAttachments(event, context);

        assertTrue(event.getAttachment("payload") instanceof MemoryRef);
        assertEquals(payload, attachments.get("payload"));
    }

    @Test
    void rejectsOutputEventAttachmentsBeforeStoringThem() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> attachments =
                Map.of("zeta", Map.of("value", 2), "alpha", Map.of("value", 1));
        Event event =
                new Event(
                        eventId,
                        OutputEvent.EVENT_TYPE,
                        Map.of("output", "result"),
                        new HashMap<>(attachments));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> EventAttachmentUtils.storeEventAttachments(event, context));

        assertTrue(error.getMessage().startsWith("Output events cannot carry attachments:"));
    }

    @Test
    void loadsEventAttachments() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("value", "original");
        MemoryRef reference =
                sensoryMemory.set(
                        EventAttachmentUtils.buildAttachmentPath(eventId, "payload"), payload);
        Event event =
                new Event(
                        eventId,
                        "AttachmentStep",
                        Map.of(),
                        new HashMap<>(Map.of("payload", reference)));

        EventAttachmentUtils.loadEventAttachments(event, context);

        assertEquals(payload, event.getAttachment("payload"));
    }

    @Test
    void buildsAttachmentPath() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String path = EventAttachmentUtils.buildAttachmentPath(eventId, "payload");

        assertEquals(
                "__event_attachments__."
                        + eventId
                        + ".239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                path);
    }

    /** Mock RunnerContext for testing resolve(). */
    static class MockRunnerContext implements RunnerContext {
        private final MemoryObject memoryObject;

        MockRunnerContext(MemoryObject memoryObject) {
            this.memoryObject = memoryObject;
        }

        @Override
        public MemoryObject getShortTermMemory() {
            return null;
        }

        @Override
        public BaseLongTermMemory getLongTermMemory() throws Exception {
            return null;
        }

        @Override
        public MemoryObject getSensoryMemory() {
            return memoryObject;
        }

        @Override
        public void sendEvent(org.apache.flink.agents.api.Event event) {}

        @Override
        public FlinkAgentsMetricGroup getAgentMetricGroup() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getActionMetricGroup() {
            return null;
        }

        @Override
        public Resource getResource(String name, ResourceType type) throws Exception {
            return null;
        }

        @Override
        public ReadableConfiguration getConfig() {
            return null;
        }

        @Override
        public Map<String, Object> getActionConfig() {
            return Map.of();
        }

        @Override
        public Object getActionConfigValue(String key) {
            return null;
        }

        @Override
        public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
            return callable.call();
        }

        @Override
        public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
            return callable.call();
        }

        @Override
        public void close() throws Exception {}
    }
}
