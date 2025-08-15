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

package org.apache.flink.agents.api.logger;

import org.apache.flink.agents.api.EventFilter;

/**
 * Base interface for event logger configuration. Implementations of this interface can be used to
 * configure different types of event loggers.
 *
 * <p>This interface provides common configuration options that all event loggers should support,
 * such as event filtering capabilities.
 */
public interface EventLoggerConfig {

    /**
     * Gets the event filter for this logger configuration.
     *
     * @return the EventFilter to apply, or {@link EventFilter#ACCEPT_ALL} if no filtering is needed
     */
    default EventFilter getEventFilter() {
        return EventFilter.ACCEPT_ALL;
    }
}
