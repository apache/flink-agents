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
