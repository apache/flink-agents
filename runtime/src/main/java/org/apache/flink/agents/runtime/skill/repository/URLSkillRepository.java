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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Skill repository backed by an http(s) URL pointing to a zip.
 *
 * <p>The zip is downloaded to a temp file and extracted into a process-local temp directory. The
 * downloaded zip itself is removed once extraction completes; the extracted directory is released
 * via {@link #close()} (cascaded through {@code SkillManager} → {@code ResourceContextImpl} →
 * {@code ResourceCache} on operator close). A JVM shutdown hook acts as fallback cleanup if {@code
 * close()} is never called.
 */
public final class URLSkillRepository extends AbstractMaterializedSkillRepository {

    private static final int REQUEST_TIMEOUT_MS = 90_000;

    private final String url;

    public URLSkillRepository(String url) throws IOException {
        super(materialize(url));
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    private static SkillMaterializer.Materialized materialize(String url) throws IOException {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("Only http(s) URLs are supported: " + url);
        }
        Path tmpZip = SkillMaterializer.downloadToTempFile(url, REQUEST_TIMEOUT_MS);
        try {
            return SkillMaterializer.extractZipSafely(tmpZip);
        } finally {
            Files.deleteIfExists(tmpZip);
        }
    }
}
