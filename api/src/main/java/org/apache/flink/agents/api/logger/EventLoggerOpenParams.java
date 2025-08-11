package org.apache.flink.agents.api.logger;

import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;

public class EventLoggerOpenParams {
    private final StreamingRuntimeContext runtimeContext;

    public EventLoggerOpenParams(StreamingRuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    public StreamingRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }
}
