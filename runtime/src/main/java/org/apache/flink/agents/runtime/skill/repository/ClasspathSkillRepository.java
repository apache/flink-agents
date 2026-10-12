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

package org.apache.flink.agents.runtime.skill.repository;

import java.io.IOException;
import java.net.URLClassLoader;

/**
 * Skill repository backed by a classpath resource — either a directory under {@code
 * src/main/resources} (typical dev / Maven layout) or a path inside one or more JARs on the
 * classpath (typical deployment, including Flink fat-jar / Maven Shade output and multi-plugin-jar
 * setups).
 *
 * <p>Resource discovery is layered:
 *
 * <ol>
 *   <li>{@link ClassLoader#getResources(String)} enumerates every directly-matching URL — this
 *       covers classpath directories and JARs with explicit directory entries for the resource
 *       prefix.
 *   <li>Fallback: if the class loader is a {@link URLClassLoader}, scan its URLs for any JAR whose
 *       entries start with the prefix even when no explicit directory entry exists (some plain
 *       {@code maven-jar-plugin} setups).
 * </ol>
 *
 * <p>When multiple JAR URLs match (e.g. several plugin JARs each contributing skills under the same
 * prefix), their entries are merged into a single temp directory via {@link
 * SkillMaterializer#extractClasspathFromJars}. Same-path collisions log a WARN and last-write-wins.
 */
public final class ClasspathSkillRepository extends AbstractMaterializedSkillRepository {

    private final String resource;

    public ClasspathSkillRepository(String resource) throws IOException {
        this(resource, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Constructor that accepts an explicit class loader. Production code passes the Flink user-code
     * class loader (threaded through {@code ResourceCache}); tests may inject a {@link
     * URLClassLoader} pointing at freshly-built jars.
     */
    public ClasspathSkillRepository(String resource, ClassLoader classLoader) throws IOException {
        super(ClasspathSkillMaterializer.materialize(resource, classLoader));
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }
}
