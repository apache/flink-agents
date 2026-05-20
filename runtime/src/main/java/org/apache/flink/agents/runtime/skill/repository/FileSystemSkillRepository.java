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

import org.apache.flink.agents.runtime.skill.SkillRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem-backed {@link SkillRepository}. Accepts either a directory whose immediate
 * subdirectories each contain a {@code SKILL.md}, or a {@code .zip} file that expands into such a
 * layout.
 */
public final class FileSystemSkillRepository extends AbstractMaterializedSkillRepository {

    public FileSystemSkillRepository(Path path) {
        super(materialize(path));
    }

    public FileSystemSkillRepository(String path) {
        this(Path.of(path));
    }

    public Path getBaseDir() {
        return materialization.getDir();
    }

    private static SkillMaterializer.Materialized materialize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Path does not exist: " + resolved);
        }
        if (Files.isDirectory(resolved)) {
            return SkillMaterializer.Materialized.borrowed(resolved);
        }
        if (Files.isRegularFile(resolved) && resolved.toString().toLowerCase().endsWith(".zip")) {
            try {
                return SkillMaterializer.extractZipSafely(resolved);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to extract zip: " + resolved, e);
            }
        }
        throw new IllegalArgumentException("Path must be a directory or a .zip file: " + resolved);
    }
}
