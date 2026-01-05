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
 * Bearer token authentication for OAuth 2.0 and similar token-based authentication schemes.
 *
 * <p>This authentication method adds an "Authorization: Bearer {token}" header to requests.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * MCPServer server = MCPServer.builder("https://api.example.com/mcp")
 *     .auth(new BearerTokenAuth("your-oauth-token"))
 *     .build();
 * }</pre>
 */
public class BearerTokenAuth implements Auth {

    private static final String AUTH_TYPE = "bearer";
    private static final String FIELD_TOKEN = "token";

    @JsonProperty(FIELD_TOKEN)
    private final String token;

    /**
     * Create a new Bearer token authentication.
     *
     * @param token The bearer token to use for authentication
     */
    @JsonCreator
    public BearerTokenAuth(@JsonProperty(FIELD_TOKEN) String token) {
        this.token = Objects.requireNonNull(token, "token cannot be null");
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder) {
        requestBuilder.header("Authorization", "Bearer " + token);
    }

    @Override
    public String getAuthType() {
        return AUTH_TYPE;
    }

    public String getToken() {
        return token;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BearerTokenAuth that = (BearerTokenAuth) o;
        return Objects.equals(token, that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }

    @Override
    public String toString() {
        return "BearerTokenAuth{token=***}";
    }
}
