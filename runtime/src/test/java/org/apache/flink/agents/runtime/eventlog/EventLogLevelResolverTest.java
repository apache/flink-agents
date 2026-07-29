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

package org.apache.flink.agents.runtime.eventlog;

import org.apache.flink.agents.api.logger.EventLogLevel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventLogLevelResolverTest {

    @Test
    void testExactMatch() {
        Map<String, Object> config = new HashMap<>();
        config.put(
                "event-log.type.org.apache.flink.agents.api.event.ChatRequestEvent.level", "OFF");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        assertThat(resolver.resolve("org.apache.flink.agents.api.event.ChatRequestEvent"))
                .isEqualTo(EventLogLevel.OFF);
    }

    @Test
    void testParentPackageInheritance() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.org.apache.flink.agents.api.event.level", "VERBOSE");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        // ChatRequestEvent is under the configured parent package
        assertThat(resolver.resolve("org.apache.flink.agents.api.event.ChatRequestEvent"))
                .isEqualTo(EventLogLevel.VERBOSE);
    }

    @Test
    void testGrandparentInheritance() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.org.apache.flink.level", "OFF");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        // Should walk up multiple levels to find the match
        assertThat(resolver.resolve("org.apache.flink.agents.api.event.ChatRequestEvent"))
                .isEqualTo(EventLogLevel.OFF);
    }

    @Test
    void testRootDefault() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.level", "VERBOSE");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        assertThat(resolver.resolve("some.unknown.EventType")).isEqualTo(EventLogLevel.VERBOSE);
    }

    @Test
    void testBuiltInDefault() {
        EventLogLevelResolver resolver = new EventLogLevelResolver(Collections.emptyMap());

        assertThat(resolver.resolve("some.unknown.EventType")).isEqualTo(EventLogLevel.STANDARD);
    }

    @Test
    void testCaseInsensitive() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.my.Event.level", "verbose");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        assertThat(resolver.resolve("my.Event")).isEqualTo(EventLogLevel.VERBOSE);

        // Also test root level with mixed case
        Map<String, Object> config2 = new HashMap<>();
        config2.put("event-log.level", "Off");
        EventLogLevelResolver resolver2 = new EventLogLevelResolver(config2);

        assertThat(resolver2.resolve("any.Event")).isEqualTo(EventLogLevel.OFF);
    }

    @Test
    void testInvalidLevel() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.my.Event.level", "INVALID_LEVEL");

        assertThatThrownBy(() -> new EventLogLevelResolver(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_LEVEL");
    }

    @Test
    void testNullConfigData() {
        EventLogLevelResolver resolver = new EventLogLevelResolver(null);

        assertThat(resolver.resolve("some.Event")).isEqualTo(EventLogLevel.STANDARD);
    }

    @Test
    void testNullOrEmptyEventType() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.level", "VERBOSE");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        assertThat(resolver.resolve(null)).isEqualTo(EventLogLevel.VERBOSE);
        assertThat(resolver.resolve("")).isEqualTo(EventLogLevel.VERBOSE);
    }

    @Test
    void testExactMatchTakesPrecedenceOverParent() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.org.apache.flink.agents.api.event.level", "OFF");
        config.put(
                "event-log.type.org.apache.flink.agents.api.event.ChatRequestEvent.level",
                "VERBOSE");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        // Exact match should win over parent package
        assertThat(resolver.resolve("org.apache.flink.agents.api.event.ChatRequestEvent"))
                .isEqualTo(EventLogLevel.VERBOSE);
        // Sibling should inherit from parent
        assertThat(resolver.resolve("org.apache.flink.agents.api.event.ChatResponseEvent"))
                .isEqualTo(EventLogLevel.OFF);
    }

    @Test
    void testCachingReturnsSameResult() {
        Map<String, Object> config = new HashMap<>();
        config.put("event-log.type.my.Event.level", "OFF");
        EventLogLevelResolver resolver = new EventLogLevelResolver(config);

        // Call twice — should return cached result
        EventLogLevel first = resolver.resolve("my.Event");
        EventLogLevel second = resolver.resolve("my.Event");
        assertThat(first).isSameAs(second);
    }
}
