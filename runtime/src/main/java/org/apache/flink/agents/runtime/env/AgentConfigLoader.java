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

import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.YamlParserUtils;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selects, parses and expands Flink configuration files into flat agent options. */
final class AgentConfigLoader {
    private static final Logger LOG = LoggerFactory.getLogger(AgentConfigLoader.class);
    static final String FLINK_CONF_FILENAME = "config.yaml";
    static final String LEGACY_FLINK_CONF_FILENAME = "flink-conf.yaml";
    private static final String AGENT_CONFIG_PREFIX = "agent";

    private AgentConfigLoader() {}

    static AgentConfiguration loadAgentConfiguration(String configDir) {
        return loadAgentConfiguration(configDir, EnvironmentInformation.getVersion());
    }

    /**
     * Selects and parses the configuration file the way Flink's {@code GlobalConfiguration} does:
     * on Flink 1.x the legacy {@code flink-conf.yaml} wins and is parsed with the legacy line-based
     * rules, otherwise {@code config.yaml} is parsed as standard YAML; Flink 2.x reads only {@code
     * config.yaml}.
     */
    @VisibleForTesting
    static AgentConfiguration loadAgentConfiguration(
            String configDir, @Nullable String flinkVersion) {
        try {
            if (configDir == null) {
                return new AgentConfiguration();
            }

            File legacyConfigFile = new File(configDir, LEGACY_FLINK_CONF_FILENAME);
            if (isFlink2OrLater(flinkVersion)) {
                if (legacyConfigFile.exists()) {
                    LOG.warn(
                            "Ignoring {}: Flink {} reads only {}",
                            legacyConfigFile,
                            flinkVersion,
                            FLINK_CONF_FILENAME);
                }
            } else if (legacyConfigFile.exists()) {
                LOG.info(
                        "Using legacy YAML parser to load Flink Agents configuration from {}",
                        legacyConfigFile);
                return new AgentConfiguration(
                        agentOptions(parseLegacyConfig(legacyConfigFile), ""));
            }

            final Map<String, Object> yaml =
                    YamlParserUtils.loadYamlFile(new File(configDir, FLINK_CONF_FILENAME));
            return new AgentConfiguration(agentOptions(yaml, ""));
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load Flink Agents configuration from " + configDir, e);
        }
    }

    /** Expands keys in file order, retaining agent options with the last value for each key. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> agentOptions(Map<String, ?> config, String prefix) {
        String flatPrefix = AGENT_CONFIG_PREFIX + ".";
        Map<String, Object> options = new HashMap<>();
        config.forEach(
                (key, value) -> {
                    String fullKey = prefix + key;
                    if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
                        options.putAll(agentOptions((Map<String, ?>) value, fullKey + "."));
                    } else if (fullKey.startsWith(flatPrefix)) {
                        options.put(fullKey.substring(flatPrefix.length()), value);
                    }
                });
        return options;
    }

    /**
     * Whether {@code version} names Flink 2.0 or later. Vendor suffixes after the first {@code -}
     * are ignored; an unknown or unparseable version counts as 1.x, which tries both files.
     */
    @VisibleForTesting
    static boolean isFlink2OrLater(@Nullable String version) {
        if (version == null) {
            return false;
        }
        String major = version.split("-", 2)[0].split("\\.", 2)[0];
        try {
            return Integer.parseInt(major.trim()) >= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses the line-based format used by Flink 1.20: strip comments, split the first {@code ":
     * "}, skip invalid pairs and keep the last string value for each key.
     */
    @VisibleForTesting
    static Map<String, String> parseLegacyConfig(File file) throws IOException {
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
