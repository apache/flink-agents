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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalUrlsTest {

    @Test
    void resolvesAbsoluteFileUrl(@TempDir Path tempDir) throws IOException {
        Path file = Files.createFile(tempDir.resolve("job.jar"));
        File resolved = LocalUrls.toLocalFile(file.toUri().toURL());
        assertEquals(file.toFile(), resolved);
    }

    @Test
    void resolvesRelativeFileUrlAgainstWorkingDir(@TempDir Path tempDir) throws IOException {
        // file:../../.../job.jar parses to an opaque URI that new File(URI) rejects; it must be
        // resolved relative to the process working directory instead. Regression test for GH-966.
        Path file = Files.createFile(tempDir.resolve("job.jar"));
        Path relative = Path.of("").toAbsolutePath().relativize(file);
        URL relativeUrl = new URL("file:" + relative.toString().replace(File.separatorChar, '/'));

        File resolved = LocalUrls.toLocalFile(relativeUrl);

        assertTrue(!resolved.isAbsolute(), "a relative file URL should stay a relative File");
        assertEquals(
                file.toFile().getCanonicalFile(),
                resolved.getCanonicalFile(),
                "relative File must resolve to the same location as the absolute path");
    }

    @Test
    void rejectsNonFileUrl() throws IOException {
        URL httpUrl = new URL("http://example.com/skills.jar");
        IOException ex = assertThrows(IOException.class, () -> LocalUrls.toLocalFile(httpUrl));
        assertTrue(
                ex.getMessage().contains("Not a local file URL"),
                "expected 'Not a local file URL' in message, got: " + ex.getMessage());
    }
}
