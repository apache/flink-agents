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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Centralizes the conversion of local {@code file:} URLs to {@link File} for the classpath skill
 * loading path (direct resource materialization, JAR extraction, and the {@code URLClassLoader}
 * fallback scan). Keeping this in one place ensures the three call sites agree on how relative
 * {@code file:} URLs are resolved.
 */
final class LocalUrls {

    private LocalUrls() {}

    /**
     * Resolve a local {@code file:} URL to a {@link File}.
     *
     * <p>A Flink deployment may add user-code JARs relative to the TaskManager working directory,
     * producing relative {@code file:} URLs such as {@code file:../../flink/usrlib/job.jar}. Such a
     * URL parses to an <em>opaque</em> URI (its scheme-specific part is not an absolute path),
     * which {@code new File(URI)} rejects with {@code "URI is not hierarchical"}. This method
     * resolves the opaque URI's decoded scheme-specific part against the process working directory
     * instead.
     *
     * <p>Absolute hierarchical {@code file:} URLs keep their existing {@code new File(uri)}
     * behavior. Non-{@code file} URLs are rejected explicitly.
     *
     * @throws IOException if the URL is not a {@code file:} URL, is a malformed URI, or cannot be
     *     represented as a local {@link File}.
     */
    static File toLocalFile(URL url) throws IOException {
        if (!"file".equals(url.getProtocol())) {
            throw new IOException("Not a local file URL: " + url);
        }
        try {
            URI uri = url.toURI();
            if (uri.isOpaque()) {
                // Relative file: URL (e.g. file:../../flink/usrlib/job.jar). new File(URI)
                // rejects opaque URIs, so resolve the decoded scheme-specific part relative to
                // the working directory, which is exactly how a relative File is interpreted.
                return new File(uri.getSchemeSpecificPart());
            }
            return new File(uri);
        } catch (URISyntaxException | IllegalArgumentException e) {
            // URISyntaxException: url.toURI() rejected the URL. IllegalArgumentException:
            // new File(URI) cannot represent this file: URL as a local path, e.g. one carrying an
            // authority component like file://host/share/job.jar. Callers only catch IOException,
            // so wrap both so such a URL is skipped rather than escaping unchecked.
            throw new IOException("Malformed file URL: " + url, e);
        }
    }
}
