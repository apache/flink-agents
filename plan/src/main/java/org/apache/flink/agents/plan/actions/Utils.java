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
package org.apache.flink.agents.plan.actions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

public final class Utils {
    private static final Logger LOG = LoggerFactory.getLogger(Utils.class);
    private static final String DEFAULT_VALUE = "<unknown>";
    public static final List<String> requiredVersions =
            List.of("1.20.4", "2.0.1", "2.1.1", "2.2.0");

    static final Versions INSTANCE = new Versions();

    private Utils() {}

    /**
     * Check whether the current Flink version supports the async execution for cross-language
     * resource.
     *
     * <p>The async execution for java resource is supported only on flink with the pemja 0.6.2
     * dependency. See <a href="https://github.com/apache/flink-agents/pull/571">flink-agents</a>
     * for details.
     */
    public static boolean supportAsync() {
        String version = INSTANCE.projectVersion;

        if (DEFAULT_VALUE.equals(version)) {
            return false;
        }

        try {
            StringTokenizer st = new StringTokenizer(version, ".");
            int major = Integer.parseInt(st.nextToken());
            int minor = Integer.parseInt(st.nextToken());
            int micro = Integer.parseInt(st.nextToken());

            if ((major == 1 && (minor < 20 || (minor == 20 && micro <= 3)))
                    || (major == 2 && minor == 0 && micro <= 1)
                    || (major == 2 && minor == 1 && micro <= 1)
                    || (major == 2 && minor == 2 && micro <= 0)) {
                LOG.debug(
                        "Flink {} doesn't support async execution for java resource, will fallback to sync execution.",
                        version);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.debug("Can't decide flink version, will fallback to sync execution.", e);
            return false;
        }
    }

    private static class Versions {
        private String projectVersion = DEFAULT_VALUE;

        public Versions() {
            ClassLoader classLoader = Utils.class.getClassLoader();
            try (InputStream propFile =
                    classLoader.getResourceAsStream(".flink-runtime.version.properties")) {
                if (propFile != null) {
                    Properties properties = new Properties();
                    properties.load(propFile);
                    this.projectVersion = getProperty(properties, "project.version", DEFAULT_VALUE);
                }
            } catch (IOException ioe) {
                LOG.info(
                        "Cannot determine code revision: Unable to read version property file.: {}",
                        ioe.getMessage());
            }
        }

        private String getProperty(Properties properties, String key, String DEFAULT_VALUE) {
            String value = properties.getProperty(key);
            return value != null && value.charAt(0) != '$' ? value : DEFAULT_VALUE;
        }
    }
}
