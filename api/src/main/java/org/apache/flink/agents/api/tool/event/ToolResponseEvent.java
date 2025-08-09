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

package org.apache.flink.agents.api.tool.event;

import java.util.Objects;

/** Event representing a result from tool call. Corresponds to the Python ToolResponseEvent. */
public class ToolResponseEvent {

    private final ToolRequestEvent request;
    private final Object response;
    private final boolean success;
    private final String error;
    private final long timestamp;

    public ToolResponseEvent(ToolRequestEvent request, Object response) {
        this.request = Objects.requireNonNull(request, "request cannot be null");
        this.response = response;
        this.success = true;
        this.error = null;
        this.timestamp = System.currentTimeMillis();
    }

    public ToolResponseEvent(ToolRequestEvent request, String error) {
        this.request = Objects.requireNonNull(request, "request cannot be null");
        this.response = null;
        this.success = false;
        this.error = Objects.requireNonNull(error, "error cannot be null");
        this.timestamp = System.currentTimeMillis();
    }

    public ToolRequestEvent getRequest() {
        return request;
    }

    public Object getResponse() {
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        if (success) {
            return "ToolResponseEvent{"
                    + "request="
                    + request
                    + ", response="
                    + response
                    + ", success=true"
                    + ", timestamp="
                    + timestamp
                    + '}';
        } else {
            return "ToolResponseEvent{"
                    + "request="
                    + request
                    + ", error='"
                    + error
                    + '\''
                    + ", success=false"
                    + ", timestamp="
                    + timestamp
                    + '}';
        }
    }
}
