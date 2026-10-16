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
package org.apache.flink.agents.integrations.observability.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import java.util.Collections;
import java.util.List;

/**
 * Immutable {@link SpanData} assembled from Event Log records.
 *
 * <p>Spans are built directly as {@link SpanData} (rather than through a {@code Tracer}) because
 * the exporter must control the trace and span ids: they are derived deterministically from the
 * framework's {@code inputRunId} / {@code executionId} so that re-exports are idempotent, and the
 * SDK tracer does not allow supplying explicit ids.
 */
final class AgentTraceSpanData implements SpanData {

    private final String name;
    private final SpanKind kind;
    private final SpanContext spanContext;
    private final SpanContext parentSpanContext;
    private final StatusData status;
    private final long startEpochNanos;
    private final long endEpochNanos;
    private final Attributes attributes;
    private final Resource resource;
    private final InstrumentationScopeInfo scope;

    AgentTraceSpanData(
            String name,
            SpanKind kind,
            SpanContext spanContext,
            SpanContext parentSpanContext,
            StatusData status,
            long startEpochNanos,
            long endEpochNanos,
            Attributes attributes,
            Resource resource,
            InstrumentationScopeInfo scope) {
        this.name = name;
        this.kind = kind;
        this.spanContext = spanContext;
        this.parentSpanContext = parentSpanContext;
        this.status = status;
        this.startEpochNanos = startEpochNanos;
        this.endEpochNanos = endEpochNanos;
        this.attributes = attributes;
        this.resource = resource;
        this.scope = scope;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SpanKind getKind() {
        return kind;
    }

    @Override
    public SpanContext getSpanContext() {
        return spanContext;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return parentSpanContext;
    }

    @Override
    public StatusData getStatus() {
        return status;
    }

    @Override
    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public List<EventData> getEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<LinkData> getLinks() {
        return Collections.emptyList();
    }

    @Override
    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    @Override
    public boolean hasEnded() {
        return true;
    }

    @Override
    public int getTotalRecordedEvents() {
        return 0;
    }

    @Override
    public int getTotalRecordedLinks() {
        return 0;
    }

    @Override
    public int getTotalAttributeCount() {
        return attributes.size();
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return scope;
    }

    @Override
    @SuppressWarnings("deprecation") // SpanData still requires the deprecated accessor.
    public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(
                scope.getName(), scope.getVersion());
    }

    @Override
    public Resource getResource() {
        return resource;
    }
}
