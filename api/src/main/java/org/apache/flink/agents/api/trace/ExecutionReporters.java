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
package org.apache.flink.agents.api.trace;

import org.apache.flink.agents.api.context.RunnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.Map;

/**
 * Utilities for reporting optional nested executions through a {@link RunnerContext}.
 *
 * <p>These helpers are best-effort: reporter failures are ignored so execution reporting never
 * changes action success or failure. When reporting a failed execution, reporter failures are also
 * attached as suppressed exceptions to the business error so callers that later throw that error
 * can still inspect the reporting failure.
 */
public final class ExecutionReporters {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionReporters.class);

    private static final Map<String, Object> EMPTY_METADATA = Map.of();

    private ExecutionReporters() {}

    public static void started(RunnerContext ctx, String entityType, String entityName) {
        started(ctx, entityType, entityName, EMPTY_METADATA);
    }

    public static void started(
            RunnerContext ctx,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata) {
        report(
                ctx,
                reporter -> reporter.reportExecutionStarted(entityType, entityName, entityMetadata),
                null);
    }

    public static void succeeded(RunnerContext ctx, String entityType, String entityName) {
        succeeded(ctx, entityType, entityName, EMPTY_METADATA);
    }

    public static void succeeded(
            RunnerContext ctx,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata) {
        report(
                ctx,
                reporter ->
                        reporter.reportExecutionSucceeded(entityType, entityName, entityMetadata),
                null);
    }

    public static void failed(
            RunnerContext ctx,
            String entityType,
            String entityName,
            Throwable error,
            @Nullable String problemCategory) {
        failed(ctx, entityType, entityName, EMPTY_METADATA, error, problemCategory);
    }

    public static void failed(
            RunnerContext ctx,
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory) {
        report(
                ctx,
                reporter ->
                        reporter.reportExecutionFailed(
                                entityType, entityName, entityMetadata, error, problemCategory),
                error);
    }

    private static void report(
            RunnerContext ctx, ReporterCall reporterCall, @Nullable Throwable businessError) {
        if (ctx instanceof ExecutionReporter) {
            try {
                reporterCall.report((ExecutionReporter) ctx);
            } catch (Exception reportError) {
                if (businessError != null && businessError != reportError) {
                    businessError.addSuppressed(reportError);
                }
                LOG.debug("Execution reporting failed and was ignored.", reportError);
            }
        }
    }

    @FunctionalInterface
    private interface ReporterCall {
        void report(ExecutionReporter reporter) throws Exception;
    }
}
