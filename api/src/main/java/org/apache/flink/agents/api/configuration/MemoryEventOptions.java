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
package org.apache.flink.agents.api.configuration;

/**
 * Config options controlling memory observation events.
 *
 * <p>Resolution order per operation: the operation's own sub-key if explicitly configured, else the
 * {@code memory.generate-event} master switch if explicitly configured, else the operation's
 * built-in default (writes and all long-term ops: on; short-term/sensory reads: off).
 */
public class MemoryEventOptions {

    /** Master switch. Fallback for unset sub-switches; when unset itself, per-op defaults apply. */
    public static final ConfigOption<Boolean> MEMORY_GENERATE_EVENT =
            new ConfigOption<>("memory.generate-event", Boolean.class, null);

    public static final ConfigOption<Boolean> SHORT_TERM_WRITE =
            new ConfigOption<>("memory.generate-event.short-term-write", Boolean.class, null);

    public static final ConfigOption<Boolean> SHORT_TERM_READ =
            new ConfigOption<>("memory.generate-event.short-term-read", Boolean.class, null);

    public static final ConfigOption<Boolean> SENSORY_WRITE =
            new ConfigOption<>("memory.generate-event.sensory-write", Boolean.class, null);

    public static final ConfigOption<Boolean> SENSORY_READ =
            new ConfigOption<>("memory.generate-event.sensory-read", Boolean.class, null);

    public static final ConfigOption<Boolean> LONG_TERM_UPDATE =
            new ConfigOption<>("memory.generate-event.long-term-update", Boolean.class, null);

    public static final ConfigOption<Boolean> LONG_TERM_GET =
            new ConfigOption<>("memory.generate-event.long-term-get", Boolean.class, null);

    public static final ConfigOption<Boolean> LONG_TERM_SEARCH =
            new ConfigOption<>("memory.generate-event.long-term-search", Boolean.class, null);

    /**
     * Opt-in agent-run lifecycle event; not governed by the memory master switch. Disabled by
     * default.
     */
    public static final ConfigOption<Boolean> AGENT_RUN_BEGIN_EVENT =
            new ConfigOption<>("agent-run.begin-event", Boolean.class, false);

    private MemoryEventOptions() {}
}
