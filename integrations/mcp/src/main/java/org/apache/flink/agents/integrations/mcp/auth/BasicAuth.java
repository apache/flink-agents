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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Basic authentication (username/password) for HTTP requests.
 *
 * <p>This authentication method adds an "Authorization: Basic {credentials}" header to requests,
 * where credentials is the base64-encoded string "username:password".
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * MCPServer server = MCPServer.builder("https://api.example.com/mcp")
 *     .auth(new BasicAuth("username", "password"))
 *     .build();
 * }</pre>
 */
public class BasicAuth implements Auth {

    private static final String AUTH_TYPE = "basic";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_PASSWORD = "password";

    @JsonProperty(FIELD_USERNAME)
    private final String username;

    @JsonProperty(FIELD_PASSWORD)
    private final String password;

    /**
     * Create a new Basic authentication.
     *
     * @param username The username for authentication
     * @param password The password for authentication
     */
    @JsonCreator
    public BasicAuth(
            @JsonProperty(FIELD_USERNAME) String username,
            @JsonProperty(FIELD_PASSWORD) String password) {
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.password = Objects.requireNonNull(password, "password cannot be null");
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder) {
        String credentials = username + ":" + password;
        String encoded =
                Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        requestBuilder.header("Authorization", "Basic " + encoded);
    }

    @Override
    public String getAuthType() {
        return AUTH_TYPE;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BasicAuth basicAuth = (BasicAuth) o;
        return Objects.equals(username, basicAuth.username)
                && Objects.equals(password, basicAuth.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, password);
    }

    @Override
    public String toString() {
        return "BasicAuth{username='" + username + "', password=***}";
    }
}
