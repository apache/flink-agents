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

package org.apache.flink.agents.api.skills;

import org.apache.flink.agents.api.configuration.ConfigOption;

/**
 * Agent config options that bound skill source materialization. The download limit applies to URL
 * skill sources; the extraction limits apply to every skill archive that is extracted, including
 * local {@code .zip} sources.
 */
public class SkillOptions {

    /**
     * The maximum number of bytes accepted from a single skill archive download over HTTP(S). Must
     * be strictly positive; there is no unlimited setting. Defaults to 64 MiB.
     */
    public static final ConfigOption<Long> URL_MAX_DOWNLOAD_BYTES =
            new ConfigOption<>(
                    "skill.source.url.max-download-bytes", Long.class, 64L * 1024 * 1024);

    /**
     * The maximum uncompressed size in bytes of a single entry when a skill archive is extracted.
     * Must be strictly positive; there is no unlimited setting. Defaults to 64 MiB.
     */
    public static final ConfigOption<Long> MAX_EXTRACT_ENTRY_BYTES =
            new ConfigOption<>(
                    "skill.source.max-extract-entry-bytes", Long.class, 64L * 1024 * 1024);

    /**
     * The maximum cumulative uncompressed size in bytes across all entries of one skill archive
     * extraction. Must be strictly positive; there is no unlimited setting. Defaults to 256 MiB.
     */
    public static final ConfigOption<Long> MAX_EXTRACT_TOTAL_BYTES =
            new ConfigOption<>(
                    "skill.source.max-extract-total-bytes", Long.class, 256L * 1024 * 1024);

    /**
     * The maximum number of entries permitted in one skill archive. Must be strictly positive;
     * there is no unlimited setting. Defaults to 1000.
     */
    public static final ConfigOption<Integer> MAX_EXTRACT_ENTRIES =
            new ConfigOption<>("skill.source.max-extract-entries", Integer.class, 1_000);
}
