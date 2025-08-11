package org.apache.flink.agents.runtime.logger;

import org.apache.flink.agents.api.logger.EventLoggerConfig;

/**
 * Configuration class for file-based event logging. This class holds the file path where events
 * will be logged.
 */
public class FileEventLoggerConfig implements EventLoggerConfig {

    private final String baseEventLogDir;

    private FileEventLoggerConfig(Builder builder) {
        this.baseEventLogDir = builder.baseEventLogDir;
    }

    public String getBaseEventLogDir() {
        return baseEventLogDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseEventLogDir = System.getProperty("java.io.tmpdir");

        private Builder() {}

        public Builder baseEventLogDir(String baseEventLogDir) {
            this.baseEventLogDir = baseEventLogDir;
            return this;
        }

        public FileEventLoggerConfig build() {
            return new FileEventLoggerConfig(this);
        }
    }
}
