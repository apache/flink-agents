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

package org.apache.flink.agents.runtime.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser for the legacy Flink configuration file {@code flink-conf.yaml}.
 *
 * <p>Mirrors {@code GlobalConfiguration#loadLegacyYAMLResource} of Flink 1.20, which reads the file
 * line by line rather than as YAML: everything after the first {@code #} is a comment, key and
 * value are split on the first {@code ": "}, lines without a valid pair are skipped with a warning,
 * values are kept as strings, and a duplicate key overrides the earlier one.
 */
final class LegacyFlinkConfParser {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyFlinkConfParser.class);

    private LegacyFlinkConfParser() {}

    static Map<String, String> parse(File file) throws IOException {
        final Map<String, String> config = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String conf = line.split("#", 2)[0].trim();
                if (conf.isEmpty()) {
                    continue;
                }

                String[] kv = conf.split(": ", 2);
                if (kv.length == 1) {
                    LOG.warn(
                            "Error while trying to split key and value in configuration file {}:{}: "
                                    + "Line is not a key-value pair (missing space after ':'?)",
                            file,
                            lineNo);
                    continue;
                }

                String key = kv[0].trim();
                String value = kv[1].trim();
                if (key.isEmpty() || value.isEmpty()) {
                    LOG.warn(
                            "Error after splitting key and value in configuration file {}:{}: "
                                    + "Key or value was empty",
                            file,
                            lineNo);
                    continue;
                }

                config.put(key, value);
            }
        }

        return config;
    }
}
