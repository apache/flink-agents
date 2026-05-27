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

/**
 * A single {@code package} skill source entry: a Python package name plus a resource path relative
 * to that package's root. The {@code package} scheme is Python-only at runtime — a YAML using this
 * field deserializes on Java but fails at skill load time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class PackageSkillSpec {
    private final String packageName;
    private final String resource;

    @JsonCreator
    public PackageSkillSpec(
            @JsonProperty(value = "package", required = true) String packageName,
            @JsonProperty(value = "resource", required = true) String resource) {
        this.packageName = packageName;
        this.resource = resource;
    }

    @JsonProperty("package")
    public String getPackageName() {
        return packageName;
    }

    @JsonProperty("resource")
    public String getResource() {
        return resource;
    }
}
