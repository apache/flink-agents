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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * API key authentication for HTTP requests.
 *
 * <p>This authentication method adds a custom header with an API key to requests. Common header
 * names include "X-API-Key", "Api-Key", or any custom header.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * MCPServer server = MCPServer.builder("https://api.example.com/mcp")
 *     .auth(new ApiKeyAuth("X-API-Key", "your-api-key"))
 *     .build();
 * }</pre>
 */
public class ApiKeyAuth implements Auth {

    private static final String AUTH_TYPE = "api_key";
    private static final String FIELD_HEADER_NAME = "headerName";
    private static final String FIELD_API_KEY = "apiKey";

    @JsonProperty(FIELD_HEADER_NAME)
    private final String headerName;

    @JsonProperty(FIELD_API_KEY)
    private final String apiKey;

    /**
     * Create a new API key authentication.
     *
     * @param headerName The name of the header to use (e.g., "X-API-Key")
     * @param apiKey The API key value
     */
    @JsonCreator
    public ApiKeyAuth(
            @JsonProperty(FIELD_HEADER_NAME) String headerName,
            @JsonProperty(FIELD_API_KEY) String apiKey) {
        this.headerName = Objects.requireNonNull(headerName, "headerName cannot be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder) {
        requestBuilder.header(headerName, apiKey);
    }

    @Override
    public String getAuthType() {
        return AUTH_TYPE;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyAuth that = (ApiKeyAuth) o;
        return Objects.equals(headerName, that.headerName) && Objects.equals(apiKey, that.apiKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(headerName, apiKey);
    }

    @Override
    public String toString() {
        return "ApiKeyAuth{headerName='" + headerName + "', apiKey=***}";
    }
}
