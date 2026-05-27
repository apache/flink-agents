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

import java.util.Collections;
import java.util.List;

/**
 * Declarative Skills resource. Each list below maps to a skill source scheme:
 *
 * <ul>
 *   <li>{@code paths} — {@code local} scheme: directories or {@code .zip} files on the filesystem
 *   <li>{@code urls} — {@code url} scheme: {@code http(s)} URLs pointing to a {@code .zip}
 *   <li>{@code classpath} — {@code classpath} scheme: resource paths on the Java classpath
 *   <li>{@code package} — {@code package} scheme (Python-only at runtime): {@code (package,
 *       resource)} pairs pointing at resources inside an installed Python package
 * </ul>
 *
 * <p>At least one of the four lists must be non-empty. {@code package} is exposed on Java for YAML
 * schema parity with Python — it deserializes successfully but {@code SkillManager} on Java will
 * fail at load time because Java does not register a {@code package} handler.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class SkillsSpec {
    private final String name;
    private final List<String> paths;
    private final List<String> urls;
    private final List<String> classpath;
    private final List<PackageSkillSpec> packageEntries;

    @JsonCreator
    public SkillsSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("paths") List<String> paths,
            @JsonProperty("urls") List<String> urls,
            @JsonProperty("classpath") List<String> classpath,
            @JsonProperty("package") List<PackageSkillSpec> packageEntries) {
        this.name = name;
        this.paths = paths == null ? Collections.emptyList() : List.copyOf(paths);
        this.urls = urls == null ? Collections.emptyList() : List.copyOf(urls);
        this.classpath = classpath == null ? Collections.emptyList() : List.copyOf(classpath);
        this.packageEntries =
                packageEntries == null ? Collections.emptyList() : List.copyOf(packageEntries);
        if (this.paths.isEmpty()
                && this.urls.isEmpty()
                && this.classpath.isEmpty()
                && this.packageEntries.isEmpty()) {
            throw new IllegalArgumentException(
                    "skills '"
                            + name
                            + "': at least one of paths/urls/classpath/package must be non-empty.");
        }
    }

    public String getName() {
        return name;
    }

    public List<String> getPaths() {
        return paths;
    }

    public List<String> getUrls() {
        return urls;
    }

    public List<String> getClasspath() {
        return classpath;
    }

    @JsonProperty("package")
    public List<PackageSkillSpec> getPackageEntries() {
        return packageEntries;
    }
}
