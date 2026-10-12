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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

/** Classpath resolution shared by native Java repositories and the Python bridge. */
public final class ClasspathSkillMaterializer {
    private static final Logger LOG = LoggerFactory.getLogger(ClasspathSkillMaterializer.class);

    private ClasspathSkillMaterializer() {}

    /** Copy the resolved resources into a directory owned and cleaned up by the caller. */
    public static void materializeInto(String resource, ClassLoader classLoader, Path target)
            throws IOException {
        try (SkillMaterializer.Materialized source = materialize(resource, classLoader)) {
            SkillMaterializer.copyDirectory(source.getDir(), target);
        }
    }

    public static SkillMaterializer.Materialized materialize(
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
            p = LocalUrls.toLocalFile(url).toPath();
        } catch (IOException e) {
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
        for (ClassLoader current = classLoader; current != null; current = current.getParent()) {
            if (!(current instanceof URLClassLoader)) {
                continue;
            }
            String prefix = resource.endsWith("/") ? resource : resource + "/";
            for (URL u : ((URLClassLoader) current).getURLs()) {
                String uStr = u.toString();
                // Flink blob-cache JARs do not retain a .jar filename suffix.
                File jarFileObj;
                try {
                    jarFileObj = LocalUrls.toLocalFile(u);
                } catch (IOException e) {
                    // Skip URLs we can't resolve to a local file (non-file protocols, malformed).
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
                } catch (ZipException e) {
                    if (uStr.endsWith(".jar")) {
                        throw e;
                    }
                    // A classpath URL may also reference a non-archive file.
                }
            }
        }
        return new ArrayList<>(matches);
    }
}
