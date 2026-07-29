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

package org.apache.flink.agents.api.skills;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A single entry inside {@link Skills#getSources()}. {@code scheme} identifies the source type
 * (e.g. {@code "local"}, {@code "url"}, {@code "classpath"}); {@code params} carries the
 * scheme-specific configuration (e.g. {@code {"path": "/data/skills"}}).
 *
 * <p>The {@code scheme} is normalized to lowercase. Unknown schemes deserialize successfully — the
 * registry is the fail point at load time.
 */
public final class SkillSourceSpec {

    private final String scheme;
    private final Map<String, String> params;

    @JsonCreator
    public SkillSourceSpec(
            @JsonProperty("scheme") String scheme,
            @JsonProperty("params") Map<String, String> params) {
        this.scheme = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        this.params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }

    @JsonProperty("scheme")
    public String getScheme() {
        return scheme;
    }

    @JsonProperty("params")
    public Map<String, String> getParams() {
        return params;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillSourceSpec)) return false;
        SkillSourceSpec that = (SkillSourceSpec) o;
        return Objects.equals(scheme, that.scheme) && params.equals(that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, params);
    }

    @Override
    public String toString() {
        return "SkillSourceSpec{scheme=" + scheme + ", params=" + params + "}";
    }
}
