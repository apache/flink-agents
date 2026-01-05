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

package org.apache.flink.agents.integrations.mcp.auth;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.net.http.HttpRequest;

/**
 * Base interface for authentication mechanisms used with MCP servers.
 *
 * <p>Implementations of this interface define how to apply authentication to HTTP requests made to
 * MCP servers.
 *
 * @see BearerTokenAuth
 * @see BasicAuth
 * @see ApiKeyAuth
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "authType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BearerTokenAuth.class, name = "bearer"),
    @JsonSubTypes.Type(value = BasicAuth.class, name = "basic"),
    @JsonSubTypes.Type(value = ApiKeyAuth.class, name = "api_key")
})
public interface Auth extends Serializable {

    /**
     * Apply authentication to an HTTP request builder.
     *
     * @param requestBuilder The HTTP request builder to apply authentication to
     */
    void applyAuth(HttpRequest.Builder requestBuilder);

    /**
     * Get the type of authentication for serialization purposes.
     *
     * @return The authentication type identifier
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    String getAuthType();
}
