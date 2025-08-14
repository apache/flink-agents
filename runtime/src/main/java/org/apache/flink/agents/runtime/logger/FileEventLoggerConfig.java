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

package org.apache.flink.agents.runtime.logger;

import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.logger.EventLoggerConfig;

/**
 * Configuration class for file-based event logging. This class holds the file path where events
 * will be logged and supports event filtering.
 */
public class FileEventLoggerConfig implements EventLoggerConfig {

    private final String baseEventLogDir;
    private final EventFilter eventFilter;

    private FileEventLoggerConfig(Builder builder) {
        this.baseEventLogDir = builder.baseEventLogDir;
        this.eventFilter = builder.eventFilter;
    }

    public String getBaseEventLogDir() {
        return baseEventLogDir;
    }

    @Override
    public EventFilter getEventFilter() {
        return eventFilter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseEventLogDir = System.getProperty("java.io.tmpdir");
        private EventFilter eventFilter = EventFilter.ACCEPT_ALL;

        private Builder() {}

        public Builder baseEventLogDir(String baseEventLogDir) {
            this.baseEventLogDir = baseEventLogDir;
            return this;
        }

        public Builder eventFilter(EventFilter eventFilter) {
            this.eventFilter = eventFilter != null ? eventFilter : EventFilter.ACCEPT_ALL;
            return this;
        }

        public FileEventLoggerConfig build() {
            return new FileEventLoggerConfig(this);
        }
    }
}
