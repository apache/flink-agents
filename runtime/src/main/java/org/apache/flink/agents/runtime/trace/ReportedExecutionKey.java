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
package org.apache.flink.agents.runtime.trace;

import org.apache.flink.annotation.Internal;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime key used to match lifecycle reports for the same reported execution. */
@Internal
public final class ReportedExecutionKey implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String entityType;
    private final String entityName;
    private final Map<String, Object> entityMetadata;

    public ReportedExecutionKey(
            String entityType, String entityName, Map<String, Object> entityMetadata) {
        this.entityType = entityType;
        this.entityName = entityName;
        this.entityMetadata =
                entityMetadata == null || entityMetadata.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(entityMetadata));
    }

    public Map<String, Object> getEntityMetadata() {
        return entityMetadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReportedExecutionKey)) {
            return false;
        }
        ReportedExecutionKey that = (ReportedExecutionKey) o;
        return Objects.equals(entityType, that.entityType)
                && Objects.equals(entityName, that.entityName)
                && Objects.equals(entityMetadata, that.entityMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityName, entityMetadata);
    }
}
