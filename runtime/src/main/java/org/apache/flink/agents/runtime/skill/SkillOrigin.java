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

package org.apache.flink.agents.runtime.skill;

import java.util.Objects;

/**
 * Identifies the source from which an {@link AgentSkill} was loaded. Attached to each skill at
 * registration time and used for logging (e.g. duplicate-name WARN) and debugging.
 *
 * <p>{@code scheme} mirrors the {@code SkillSourceSpec} scheme ({@code "local"} / {@code "url"} /
 * {@code "classpath"} / {@code "package"}); {@code location} is a human-readable identifier such as
 * a filesystem path, URL, classpath resource name, or {@code <package>/<resource>}.
 */
public final class SkillOrigin {

    private final String scheme;
    private final String location;

    public SkillOrigin(String scheme, String location) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.location = Objects.requireNonNull(location, "location");
    }

    public String getScheme() {
        return scheme;
    }

    public String getLocation() {
        return location;
    }

    @Override
    public String toString() {
        return scheme + ":" + location;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillOrigin)) return false;
        SkillOrigin that = (SkillOrigin) o;
        return scheme.equals(that.scheme) && location.equals(that.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, location);
    }
}
