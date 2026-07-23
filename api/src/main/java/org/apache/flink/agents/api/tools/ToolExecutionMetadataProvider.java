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
package org.apache.flink.agents.api.tools;

import java.util.Map;

/**
 * Optional Tool capability for adding structured metadata to Tool executions.
 *
 * <p>The returned metadata is supplemental: it must not define execution identity, parent/child
 * relationships, or event payload. Implementations should keep values small, stable, and JSON
 * serializable.
 */
public interface ToolExecutionMetadataProvider {

    /**
     * Returns additional metadata for the current tool call.
     *
     * @param parameters tool call parameters associated with the execution
     * @return small structured metadata to merge into execution {@code entity_metadata}
     */
    Map<String, Object> getToolExecutionMetadata(ToolParameters parameters);
}
