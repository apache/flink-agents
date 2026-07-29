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

import org.apache.flink.agents.runtime.skill.AgentSkill;
import org.apache.flink.agents.runtime.skill.SkillRepository;

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Base class for {@link SkillRepository} implementations whose skills live on disk in a {@link
 * SkillMaterializer.Materialized} directory. Subclasses contribute only the source-specific I/O
 * that produces that {@code Materialized}; reading and close lifecycle are handled here.
 *
 * <p>The directory may be borrowed (already on disk, e.g. a filesystem source) or owned (extracted
 * from a zip / jar / download). {@link SkillMaterializer.Materialized#close()} collapses both
 * cases, so the base {@link #close()} is unconditional.
 */
public abstract class AbstractMaterializedSkillRepository implements SkillRepository {

    protected final SkillMaterializer.Materialized materialization;
    private final SkillDirectoryReader reader;

    protected AbstractMaterializedSkillRepository(SkillMaterializer.Materialized materialization) {
        this.materialization = materialization;
        this.reader = new SkillDirectoryReader(materialization.getDir());
    }

    @Override
    @Nullable
    public final AgentSkill getSkill(String name) {
        return reader.getSkill(name);
    }

    @Override
    public final List<AgentSkill> getSkills() {
        return reader.getSkills();
    }

    @Override
    public final Map<String, String> getResources(String name) {
        return reader.getResources(name);
    }

    @Override
    public final Path getSkillDir(String name) {
        return reader.getSkillDir(name);
    }

    @Override
    public final void close() {
        materialization.close();
    }
}
