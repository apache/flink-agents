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

import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link EventType}. */
class EventTypeTest {

    @BeforeEach
    @AfterEach
    void resetUserRegistry() {
        EventType.clearUserRegisteredForTesting();
    }

    // -----------------------------------------------------------------------
    // Built-in constants must agree byte-for-byte with XxxEvent.EVENT_TYPE.
    // This is the consistency invariant called out in the EventType Javadoc.
    // -----------------------------------------------------------------------

    @Test
    void builtInConstantsMatchEventClassConstants() {
        assertEquals(InputEvent.EVENT_TYPE, EventType.InputEvent);
        assertEquals(OutputEvent.EVENT_TYPE, EventType.OutputEvent);
        assertEquals(ChatRequestEvent.EVENT_TYPE, EventType.ChatRequestEvent);
        assertEquals(ChatResponseEvent.EVENT_TYPE, EventType.ChatResponseEvent);
        assertEquals(ToolRequestEvent.EVENT_TYPE, EventType.ToolRequestEvent);
        assertEquals(ToolResponseEvent.EVENT_TYPE, EventType.ToolResponseEvent);
        assertEquals(
                ContextRetrievalRequestEvent.EVENT_TYPE, EventType.ContextRetrievalRequestEvent);
        assertEquals(
                ContextRetrievalResponseEvent.EVENT_TYPE, EventType.ContextRetrievalResponseEvent);
    }

    @Test
    void lookupReturnsBuiltInsByShortName() {
        assertEquals(InputEvent.EVENT_TYPE, EventType.lookup("InputEvent"));
        assertEquals(OutputEvent.EVENT_TYPE, EventType.lookup("OutputEvent"));
        assertEquals(ChatResponseEvent.EVENT_TYPE, EventType.lookup("ChatResponseEvent"));
    }

    @Test
    void lookupReturnsNullForUnknown() {
        assertNull(EventType.lookup("DoesNotExist"));
        assertNull(EventType.lookup(""));
        assertNull(EventType.lookup(null));
    }

    @Test
    void lookupOrSelfReturnsInputForUnknown() {
        // Raw EVENT_TYPE strings pass through unchanged.
        assertEquals("_input_event", EventType.lookupOrSelf("_input_event"));
        // Custom strings pass through unchanged.
        assertEquals("MyCustomEvent", EventType.lookupOrSelf("MyCustomEvent"));
        // CEL expressions pass through unchanged.
        String cel = "type == '_input_event' && price >= 125";
        assertEquals(cel, EventType.lookupOrSelf(cel));
    }

    @Test
    void isKnownTracksBuiltins() {
        assertTrue(EventType.isKnown("InputEvent"));
        assertTrue(EventType.isKnown("ChatResponseEvent"));
        assertFalse(EventType.isKnown("_input_event"));
        assertFalse(EventType.isKnown("DoesNotExist"));
        assertFalse(EventType.isKnown(""));
        assertFalse(EventType.isKnown(null));
    }

    @Test
    void allReturnsSnapshotIncludingBuiltins() {
        Map<String, String> all = EventType.all();
        assertEquals(8, all.size());
        assertEquals("_input_event", all.get("InputEvent"));
        assertEquals("_chat_response_event", all.get("ChatResponseEvent"));
        // Snapshot is unmodifiable.
        assertThrows(UnsupportedOperationException.class, () -> all.put("Foo", "bar"));
    }

    // -----------------------------------------------------------------------
    // User registration.
    // -----------------------------------------------------------------------

    public static class MyCustomEvent extends Event {
        public static final String EVENT_TYPE = "_my_custom_event";

        public MyCustomEvent() {
            super(EVENT_TYPE);
        }
    }

    public static class AnotherEvent extends Event {
        public static final String EVENT_TYPE = "_another_event";

        public AnotherEvent() {
            super(EVENT_TYPE);
        }
    }

    @Test
    void registerExposesUserDefinedEvent() {
        EventType.register(MyCustomEvent.class);
        assertEquals("_my_custom_event", EventType.lookup("MyCustomEvent"));
        assertTrue(EventType.isKnown("MyCustomEvent"));
        assertEquals(9, EventType.all().size());
    }

    @Test
    void registerIsIdempotentForSamePair() {
        EventType.register(MyCustomEvent.class);
        EventType.register(MyCustomEvent.class); // must not throw
        assertEquals("_my_custom_event", EventType.lookup("MyCustomEvent"));
    }

    /** Re-registering the same simple name with a different EVENT_TYPE must fail loudly. */
    public static class CollidingEvent extends Event {
        public static final String EVENT_TYPE = "_different_event";

        public CollidingEvent() {
            super(EVENT_TYPE);
        }
    }

    public static class ShadowingMyCustomEvent extends Event {
        public static final String EVENT_TYPE = "_shadow_event";

        public ShadowingMyCustomEvent() {
            super(EVENT_TYPE);
        }
    }

    @Test
    void registerDifferentClassesWithDifferentSimpleNamesCoexist() {
        EventType.register(MyCustomEvent.class);
        EventType.register(ShadowingMyCustomEvent.class);
        assertEquals("_my_custom_event", EventType.lookup("MyCustomEvent"));
        assertEquals("_shadow_event", EventType.lookup("ShadowingMyCustomEvent"));
    }

    /**
     * Direct test of the conflict path: register one class, then register a second class whose
     * {@code getSimpleName()} happens to collide, with a different EVENT_TYPE. We construct the
     * collision by using two top-level-style nested classes whose simple names match.
     */
    @Test
    void registerRejectsSameNameDifferentEventType() {
        EventType.register(MyCustomEvent.class);
        // Manually trigger the conflict by re-using the registry with a synthetic mapping.
        // Easiest path: declare a second class with the same simple name in a different
        // enclosing scope.
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> EventType.register(Nested.MyCustomEvent.class));
        assertTrue(ex.getMessage().contains("already registered"));
    }

    static class Nested {
        public static class MyCustomEvent extends Event {
            public static final String EVENT_TYPE = "_nested_my_custom_event";

            public MyCustomEvent() {
                super(EVENT_TYPE);
            }
        }
    }

    @Test
    void registerRejectsCollisionWithBuiltIn() {
        // A user-defined class whose simple name equals a built-in must be rejected.
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> EventType.register(MyInputEvent.InputEvent.class));
        assertTrue(ex.getMessage().contains("collides with a built-in"));
    }

    // Wrap in an enclosing class so we can name the inner class "InputEvent" without colliding
    // with the api package's InputEvent at the source level.
    static class MyInputEvent {
        public static class InputEvent extends Event {
            public static final String EVENT_TYPE = "_user_input_event";

            public InputEvent() {
                super(EVENT_TYPE);
            }
        }
    }

    public static class NoEventTypeField extends Event {
        public NoEventTypeField() {
            super("_x");
        }
    }

    @Test
    void registerRejectsClassWithoutEventTypeField() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> EventType.register(NoEventTypeField.class));
        assertTrue(ex.getMessage().contains("EVENT_TYPE"));
    }

    @Test
    void registerRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> EventType.register(null));
    }

    // -----------------------------------------------------------------------
    // Concurrency: two threads registering the same class should both succeed
    // (idempotent), and the registry should hold exactly one entry.
    // -----------------------------------------------------------------------

    @Test
    void concurrentRegisterIsSafe() throws InterruptedException {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                EventType.register(MyCustomEvent.class);
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
            assertEquals("_my_custom_event", EventType.lookup("MyCustomEvent"));
            // Built-in count (8) + our one registration = 9
            assertEquals(9, EventType.all().size());
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot independence.
    // -----------------------------------------------------------------------

    @Test
    void allReturnsSnapshotNotLiveView() {
        Map<String, String> before = EventType.all();
        EventType.register(MyCustomEvent.class);
        Map<String, String> after = EventType.all();
        assertNotNull(before);
        // Snapshot before registration must not contain the new entry.
        assertFalse(before.containsKey("MyCustomEvent"));
        assertTrue(after.containsKey("MyCustomEvent"));
        assertEquals(new HashMap<>(before).size() + 1, after.size());
    }
}
