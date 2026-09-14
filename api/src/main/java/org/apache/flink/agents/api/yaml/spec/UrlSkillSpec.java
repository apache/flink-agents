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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;

/** Declarative configuration for one URL-backed skill archive. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class UrlSkillSpec {
    private final String url;
    @Nullable private final String sha256;
    private final boolean allowInsecureHttp;

    @JsonCreator
    private UrlSkillSpec(
            @JsonProperty(value = "url", required = true) String url,
            @JsonProperty("sha256") @Nullable String sha256,
            @JsonProperty("allow_insecure_http") @Nullable JsonNode allowInsecureHttp) {
        this(url, sha256, requireAllowInsecureHttp(allowInsecureHttp));
    }

    public UrlSkillSpec(String url, @Nullable String sha256, boolean allowInsecureHttp) {
        this.url = url;
        this.sha256 = sha256;
        this.allowInsecureHttp = allowInsecureHttp;
    }

    private static boolean requireAllowInsecureHttp(@Nullable JsonNode value) {
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("allow_insecure_http must be a boolean");
        }
        return value.booleanValue();
    }

    public String getUrl() {
        return url;
    }

    @Nullable
    public String getSha256() {
        return sha256;
    }

    @JsonProperty("allow_insecure_http")
    public boolean isAllowInsecureHttp() {
        return allowInsecureHttp;
    }
}
