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
