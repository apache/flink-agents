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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

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

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathSkillRepository.class);

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
        super(materialize(resource, classLoader));
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    private static SkillMaterializer.Materialized materialize(
            String resource, ClassLoader classLoader) throws IOException {
        List<URL> matches = findAllMatches(resource, classLoader);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Classpath resource not found: " + resource);
        }

        // Group by protocol: jar URLs merge cleanly into one temp dir via
        // extractClasspathFromJars; file URLs (dir/zip) we don't merge because borrowed dirs
        // can't be combined without copying, and multiple file:// matches for the same resource
        // are rare in practice (only IDE / single-module layouts).
        List<URL> jarUrls =
                matches.stream()
                        .filter(u -> "jar".equals(u.getProtocol()))
                        .collect(Collectors.toList());
        List<URL> fileUrls =
                matches.stream()
                        .filter(u -> "file".equals(u.getProtocol()))
                        .collect(Collectors.toList());

        if (!fileUrls.isEmpty() && !jarUrls.isEmpty()) {
            LOG.warn(
                    "Classpath resource {} matched both file:// and jar: URLs; only the first"
                            + " file URL is used, the jar URLs are ignored. Matches: {}",
                    resource,
                    matches);
            return materializeFileUrl(fileUrls.get(0), resource);
        }
        if (!fileUrls.isEmpty()) {
            if (fileUrls.size() > 1) {
                LOG.warn(
                        "Classpath resource {} matched {} file URLs; using the first."
                                + " Matches: {}",
                        resource,
                        fileUrls.size(),
                        fileUrls);
            }
            return materializeFileUrl(fileUrls.get(0), resource);
        }
        // All matches are jar protocol — merge them.
        if (jarUrls.size() > 1) {
            LOG.info(
                    "Classpath resource {} matched {} JARs; merging entries from all of them.",
                    resource,
                    jarUrls.size());
        }
        return SkillMaterializer.extractClasspathFromJars(jarUrls, resource);
    }

    private static SkillMaterializer.Materialized materializeFileUrl(URL url, String resource)
            throws IOException {
        Path p;
        try {
            p = Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Bad classpath URL: " + url, e);
        }
        if (Files.isDirectory(p)) {
            return SkillMaterializer.Materialized.borrowed(p);
        }
        if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".zip")) {
            return SkillMaterializer.extractZipSafely(p);
        }
        throw new IllegalArgumentException(
                "Classpath resource must be a directory or a .zip: " + p);
    }

    /**
     * Returns every distinct URL that resolves the given classpath {@code resource}. Combines
     * {@link ClassLoader#getResources(String)} with a {@link URLClassLoader#getURLs()} scan to
     * cover JARs without explicit directory entries. Order is preserved; duplicates removed.
     */
    private static List<URL> findAllMatches(String resource, ClassLoader classLoader)
            throws IOException {
        Set<URL> matches = new LinkedHashSet<>();
        Enumeration<URL> direct = classLoader.getResources(resource);
        while (direct.hasMoreElements()) {
            matches.add(direct.nextElement());
        }
        if (classLoader instanceof URLClassLoader) {
            String prefix = resource.endsWith("/") ? resource : resource + "/";
            for (URL u : ((URLClassLoader) classLoader).getURLs()) {
                String uStr = u.toString();
                if (!uStr.endsWith(".jar")) {
                    continue;
                }
                File jarFileObj;
                try {
                    jarFileObj = new File(u.toURI());
                } catch (URISyntaxException e) {
                    continue;
                }
                if (!jarFileObj.isFile()) {
                    continue;
                }
                try (JarFile jf = new JarFile(jarFileObj)) {
                    Enumeration<JarEntry> entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        if (entries.nextElement().getName().startsWith(prefix)) {
                            matches.add(new URL("jar:" + uStr + "!/" + resource));
                            break;
                        }
                    }
                }
            }
        }
        return new ArrayList<>(matches);
    }
}
