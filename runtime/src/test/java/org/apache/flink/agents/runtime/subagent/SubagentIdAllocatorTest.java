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
package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubagentIdAllocator}: same namespace inputs plus the same call sequence
 * must reproduce byte-for-byte identical ids, and any difference in the caller-side namespace
 * inputs must separate the id stream.
 */
class SubagentIdAllocatorTest {

    private static SubagentIdAllocator newAllocator(
            Object key, long sequenceNumber, String actionName, Object eventInput) {
        return new SubagentIdAllocator(key, sequenceNumber, actionName, new InputEvent(eventInput));
    }

    @Test
    void eventInstanceIdDoesNotAffectNamespace() {
        // The namespace keeps only the event's type and attributes, so a failover replay that
        // re-creates the event object (with a new event id) reproduces the same ids.
        SubagentIdAllocator ctx1 =
                new SubagentIdAllocator("key-1", 7L, "actionA", new InputEvent("payload"));
        SubagentIdAllocator ctx2 =
                new SubagentIdAllocator("key-1", 7L, "actionA", new InputEvent("payload"));

        assertEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void attributeMapIterationOrderDoesNotAffectNamespace() {
        // A replay on another JVM may iterate the rebuilt attribute map differently, so the digest
        // mapper sorts entries: identical contents in reversed order must yield the same id.
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("alpha", "1");
        forward.put("beta", "2");
        forward.put("gamma", "3");
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("gamma", "3");
        reversed.put("beta", "2");
        reversed.put("alpha", "1");

        SubagentIdAllocator ctx1 =
                new SubagentIdAllocator("key-1", 7L, "actionA", new Event("my.EventType", forward));
        SubagentIdAllocator ctx2 =
                new SubagentIdAllocator(
                        "key-1", 7L, "actionA", new Event("my.EventType", reversed));

        assertEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void deterministicAcrossTwoInstancesWithSameNamespace() {
        SubagentIdAllocator ctx1 = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator ctx2 = newAllocator("key-1", 7L, "actionA", "e-1");

        // Same call sequence replayed on two independently constructed instances that share an
        // identical namespace must reproduce byte-for-byte identical ids -- this is the core
        // property that makes failover replay reproduce the same sub-agent identities.
        String session1a = ctx1.nextSessionId();
        String session2a = ctx2.nextSessionId();
        assertEquals(session1a, session2a);

        String call1a = ctx1.nextCallId(session1a);
        String call2a = ctx2.nextCallId(session2a);
        assertEquals(call1a, call2a);

        String session1b = ctx1.nextSessionId();
        String session2b = ctx2.nextSessionId();
        assertEquals(session1b, session2b);
        assertNotEquals(session1a, session1b);
    }

    @Test
    void namespaceSeparatesOnKey() {
        SubagentIdAllocator ctx1 = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator ctx2 = newAllocator("key-2", 7L, "actionA", "e-1");

        // The ordinal is 0 on the first call for both instances, so any difference in the
        // returned id must come from the namespace digest alone.
        assertNotEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void namespaceSeparatesOnSequenceNumber() {
        SubagentIdAllocator ctx1 = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator ctx2 = newAllocator("key-1", 8L, "actionA", "e-1");

        assertNotEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void namespaceSeparatesOnActionName() {
        SubagentIdAllocator ctx1 = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator ctx2 = newAllocator("key-1", 7L, "actionB", "e-1");

        assertNotEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void namespaceSeparatesOnTriggeringEventAloneSiblingTaskScenario() {
        // Two sibling tasks sharing key/sequenceNumber/actionName but triggered by different
        // events: the event attributes alone must keep their identities from colliding.
        SubagentIdAllocator ctx1 = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator ctx2 = newAllocator("key-1", 7L, "actionA", "e-2");

        assertNotEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void namespaceSeparatesOnAgentName() {
        // Two sub-agents of the same action execution share the caller's counting range but must
        // never hand out the same ids: the agent name alone separates their namespaces.
        SubagentIdAllocator ctx1 =
                new SubagentIdAllocator("key-1", 7L, "actionA", new InputEvent("e-1"), "agent-a");
        SubagentIdAllocator ctx2 =
                new SubagentIdAllocator("key-1", 7L, "actionA", new InputEvent("e-1"), "agent-b");

        assertNotEquals(ctx1.nextSessionId(), ctx2.nextSessionId());
    }

    @Test
    void nullAgentNameKeepsIdsStableAgainstUnnamedAllocator() {
        // A null agent name is omitted from the digest, so an allocator with a null agent name
        // reproduces exactly the ids of the unnamed one.
        SubagentIdAllocator unnamed = newAllocator("key-1", 7L, "actionA", "e-1");
        SubagentIdAllocator nullNamed =
                new SubagentIdAllocator("key-1", 7L, "actionA", new InputEvent("e-1"), null);

        assertEquals(unnamed.nextSessionId(), nullNamed.nextSessionId());
    }

    @Test
    void sessionOrdinalsIncreaseAndAreUnique() {
        SubagentIdAllocator ctx = newAllocator("key-1", 1L, "actionA", "e-1");

        String s0 = ctx.nextSessionId();
        String s1 = ctx.nextSessionId();
        String s2 = ctx.nextSessionId();

        // The namespace digest is a fixed-length UUID string, so "-<n>" unambiguously identifies
        // the ordinal suffix.
        assertTrue(s0.endsWith("-0"));
        assertTrue(s1.endsWith("-1"));
        assertTrue(s2.endsWith("-2"));

        Set<String> unique = new HashSet<>();
        unique.add(s0);
        unique.add(s1);
        unique.add(s2);
        assertEquals(3, unique.size());
    }

    @Test
    void perSessionCallOrdinalStartsAtOneAndIncrementsPerSession() {
        SubagentIdAllocator ctx = newAllocator("key-1", 1L, "actionA", "e-1");

        String sessionA = ctx.nextSessionId();
        String sessionB = ctx.nextSessionId();

        String callA1 = ctx.nextCallId(sessionA);
        String callA2 = ctx.nextCallId(sessionA);
        String callB1 = ctx.nextCallId(sessionB);

        assertTrue(callA1.endsWith("-1"));
        assertTrue(callA2.endsWith("-2"));
        // A different session's ordinal is tracked independently and also starts at 1, rather
        // than continuing sessionA's running count.
        assertTrue(callB1.endsWith("-1"));

        assertNotEquals(callA1, callA2);
        assertNotEquals(callA1, callB1);
    }

    @Test
    void callIdIsSessionIdPlusOrdinal() {
        SubagentIdAllocator ctx = newAllocator("key-1", 1L, "actionA", "e-1");

        // The call id is formed by appending the per-session ordinal to the session id -- the
        // session id already carries the namespace digest, so no further hashing is involved.
        String sessionId = ctx.nextSessionId();
        assertEquals(sessionId + "-1", ctx.nextCallId(sessionId));
        assertEquals(sessionId + "-2", ctx.nextCallId(sessionId));
    }

    @Test
    void explicitSessionIdIsUsedVerbatimNotParsed() {
        SubagentIdAllocator ctx = newAllocator("key-1", 1L, "actionA", "e-1");

        // Caller-supplied session ids need not follow the "{digest}-{ordinal}" shape; any string
        // is legal and distinct ids never collide (they must not be reused across executions).
        String callForExplicit1 = ctx.nextCallId("checkout-session-42");
        String callForExplicit2 = ctx.nextCallId("checkout-session-43");
        assertEquals("checkout-session-42-1", callForExplicit1);
        assertEquals("checkout-session-43-1", callForExplicit2);
        assertNotEquals(callForExplicit1, callForExplicit2);

        // Determinism holds for explicit session ids too: a second, freshly constructed allocator
        // sharing the same namespace reproduces the same id for the same explicit session id.
        SubagentIdAllocator ctx2 = newAllocator("key-1", 1L, "actionA", "e-1");
        assertEquals(callForExplicit1, ctx2.nextCallId("checkout-session-42"));
    }

    @Test
    void idsAreOpaqueAndDoNotLeakPlainKeyOrActionName() {
        SubagentIdAllocator ctx = newAllocator("super-secret-key", 1L, "myCustomActionName", "e-1");

        String sessionId = ctx.nextSessionId();
        String callId = ctx.nextCallId(sessionId);

        assertFalse(sessionId.contains("super-secret-key"));
        assertFalse(sessionId.contains("myCustomActionName"));
        assertFalse(callId.contains("super-secret-key"));
        assertFalse(callId.contains("myCustomActionName"));
    }
}
