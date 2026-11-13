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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural parity check between the checked-in {@code docs/yaml-schema.json} and the Java YAML
 * spec DTOs. The Pydantic models are the wire-format ground truth; this test ensures the Java POJOs
 * that consume the same wire format don't silently drift in either direction (missing property,
 * wrong required field, additionalProperties strictness mismatch).
 *
 * <p>Reads each {@code $defs} entry from the schema, finds the matching Java class by simple name,
 * and asserts: same property names, same {@code required} set, same {@code additionalProperties}
 * stance. Also checks the top-level document and the {@code MessageRole} enum.
 *
 * <p>Counterpart of Python's {@code test_checked_in_schema_matches_pydantic_models} in {@code
 * python/flink_agents/api/yaml/tests/test_specs.py}.
 */
class SchemaParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** $defs simple-name → Java spec class. Top-level document handled separately. */
    private static final Map<String, Class<?>> SPEC_CLASSES;

    static {
        Map<String, Class<?>> m = new HashMap<>();
        m.put("ActionSpec", ActionSpec.class);
        m.put("AgentSpec", AgentSpec.class);
        m.put("DescriptorSpec", DescriptorSpec.class);
        m.put("InternalSubagentSpec", InternalSubagentSpec.class);
        m.put("PromptMessage", PromptMessage.class);
        m.put("PromptSpec", PromptSpec.class);
        m.put("SkillsSpec", SkillsSpec.class);
        m.put("PackageSkillSpec", PackageSkillSpec.class);
        m.put("ToolSpec", ToolSpec.class);
        m.put("UrlSkillSpec", UrlSkillSpec.class);
        m.put("InjectedArg", ToolParameterInjection.class);
        SPEC_CLASSES = Map.copyOf(m);
    }

    /**
     * Content blocks a prompt message can hold, in the {@code ChatMessage} block format. Their
     * {@code type} is Jackson's type id rather than a creator property, and their required fields
     * are checked by their constructors, so only property names and strictness are compared.
     */
    private static final Map<String, Class<?>> CONTENT_BLOCK_CLASSES =
            Map.of(
                    "TextBlock", TextBlock.class,
                    "ImageBlock", ImageBlock.class,
                    "AudioBlock", AudioBlock.class,
                    "VideoBlock", VideoBlock.class,
                    "DocumentBlock", DocumentBlock.class,
                    "Base64Source", Base64Source.class,
                    "UrlSource", UrlSource.class);

    @Test
    void checkedInSchemaMatchesJavaSpecs() throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(schemaFile()));
        JsonNode defs = schema.get("$defs");
        assertThat(defs).as("docs/yaml-schema.json missing $defs").isNotNull();

        Set<String> seen = new HashSet<>();
        for (Iterator<String> it = defs.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            seen.add(name);
            JsonNode def = defs.get(name);
            if ("MessageRole".equals(name)) {
                assertMessageRoleEnum(def);
                continue;
            }
            if ("ToolParameterSource".equals(name)) {
                assertToolParameterSourceEnum(def);
                continue;
            }
            if (CONTENT_BLOCK_CLASSES.containsKey(name)) {
                assertContentBlockDefMatchesClass(name, def, CONTENT_BLOCK_CLASSES.get(name));
                continue;
            }
            Class<?> javaClass = SPEC_CLASSES.get(name);
            assertThat(javaClass).as("no Java spec for $defs/%s", name).isNotNull();
            assertDefMatchesClass(name, def, javaClass);
        }

        // Every Java spec in the map must have appeared in $defs.
        Set<String> expectedSchemaDefs = new HashSet<>(SPEC_CLASSES.keySet());
        expectedSchemaDefs.addAll(CONTENT_BLOCK_CLASSES.keySet());
        expectedSchemaDefs.add("MessageRole");
        expectedSchemaDefs.add("ToolParameterSource");
        assertThat(seen).as("$defs entries").isEqualTo(expectedSchemaDefs);

        // Top-level YAML document.
        assertDefMatchesClass("YamlAgentsDocument", schema, YamlAgentsDocument.class);
    }

    private static void assertMessageRoleEnum(JsonNode def) {
        Set<String> schemaValues = new HashSet<>();
        for (JsonNode v : def.get("enum")) {
            schemaValues.add(v.asText());
        }
        Set<String> javaValues =
                Arrays.stream(MessageRole.values())
                        .map(MessageRole::getValue)
                        .collect(Collectors.toSet());
        assertThat(schemaValues).as("MessageRole enum values").isEqualTo(javaValues);
    }

    private static void assertToolParameterSourceEnum(JsonNode def) {
        Set<String> schemaValues = new HashSet<>();
        for (JsonNode v : def.get("enum")) {
            schemaValues.add(v.asText());
        }
        Set<String> javaValues =
                Arrays.stream(ToolParameterSource.values())
                        .map(ToolParameterSource::getValue)
                        .collect(Collectors.toSet());
        assertThat(schemaValues).as("ToolParameterSource enum values").isEqualTo(javaValues);
    }

    private static void assertDefMatchesClass(String defName, JsonNode def, Class<?> clz) {
        Map<String, Boolean> creatorProps = creatorProperties(clz);
        Set<String> jacksonNames = creatorProps.keySet();

        Set<String> schemaProps = new HashSet<>();
        JsonNode propsNode = def.get("properties");
        if (propsNode != null) {
            for (Iterator<String> it = propsNode.fieldNames(); it.hasNext(); ) {
                schemaProps.add(it.next());
            }
        }

        if (hasJsonAnySetter(clz)) {
            // DescriptorSpec captures arbitrary extras via @JsonAnySetter; Jackson's known
            // properties are a strict subset of what the schema declares.
            assertThat(jacksonNames)
                    .as("%s: Java POJO is missing schema-declared properties", defName)
                    .containsAll(schemaProps);
        } else {
            assertThat(jacksonNames)
                    .as("%s: Java POJO properties drift from schema", defName)
                    .isEqualTo(schemaProps);
        }

        Set<String> schemaRequired = new HashSet<>();
        JsonNode requiredNode = def.get("required");
        if (requiredNode != null) {
            for (JsonNode r : requiredNode) {
                schemaRequired.add(r.asText());
            }
        }
        Set<String> jacksonRequired =
                creatorProps.entrySet().stream()
                        .filter(Map.Entry::getValue)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
        assertThat(jacksonRequired)
                .as("%s: required fields drift from schema", defName)
                .isEqualTo(schemaRequired);

        // additionalProperties: schema is the source of truth. Pydantic emits ``false`` for
        // strict specs and ``true`` for ``extra="allow"`` (DescriptorSpec). The top-level
        // YamlAgentsDocument omits the key (Pydantic default = strict), so treat absence as
        // false too.
        boolean schemaAllowsExtra =
                def.has("additionalProperties") && def.get("additionalProperties").asBoolean();
        boolean javaAllowsExtra = javaAllowsAdditional(clz);
        assertThat(javaAllowsExtra)
                .as("%s: additionalProperties drift from schema", defName)
                .isEqualTo(schemaAllowsExtra);
    }

    /**
     * Collect the wire-format property names + required flag declared on the {@code @JsonCreator}
     * constructor of {@code clz}. Reading from the creator (rather than serialization-time bean
     * introspection) avoids picking up the Java camelCase field/getter names as duplicate aliases
     * of the snake_case JSON keys.
     */
    private static void assertContentBlockDefMatchesClass(
            String defName, JsonNode def, Class<?> clz) {
        Set<String> schemaProps = new HashSet<>();
        for (Iterator<String> it = def.get("properties").fieldNames(); it.hasNext(); ) {
            schemaProps.add(it.next());
        }
        assertThat(schemaProps)
                .as("%s: schema lacks the type discriminator", defName)
                .contains("type");
        schemaProps.remove("type");
        Set<String> schemaRequired = new HashSet<>();
        for (JsonNode r : def.path("required")) {
            schemaRequired.add(r.asText());
        }
        assertThat(schemaRequired)
                .as("%s: schema must require the type discriminator", defName)
                .contains("type");
        assertThat(creatorProperties(clz).keySet())
                .as("%s: Java block properties drift from schema", defName)
                .isEqualTo(schemaProps);
        assertThat(def.get("additionalProperties").asBoolean())
                .as("%s: additionalProperties drift from schema", defName)
                .isEqualTo(javaAllowsAdditional(clz));
    }

    private static Map<String, Boolean> creatorProperties(Class<?> clz) {
        Executable creator = null;
        for (Constructor<?> c : clz.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(JsonCreator.class)) {
                creator = c;
                break;
            }
        }
        // TextBlock declares its creator as a static factory.
        for (Method m : clz.getDeclaredMethods()) {
            if (creator == null
                    && Modifier.isStatic(m.getModifiers())
                    && m.isAnnotationPresent(JsonCreator.class)) {
                creator = m;
            }
        }
        if (creator == null) {
            throw new IllegalStateException("No @JsonCreator on " + clz.getName());
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        Parameter[] params = creator.getParameters();
        Annotation[][] parameterAnnotations = creator.getParameterAnnotations();
        for (int i = 0; i < params.length; i++) {
            JsonProperty jp = null;
            for (Annotation a : parameterAnnotations[i]) {
                if (a instanceof JsonProperty) {
                    jp = (JsonProperty) a;
                    break;
                }
            }
            if (jp == null) {
                throw new IllegalStateException(
                        "Creator parameter "
                                + params[i].getName()
                                + " on "
                                + clz.getName()
                                + " is missing @JsonProperty — Jackson can't deserialize it");
            }
            out.put(jp.value(), jp.required());
        }
        return out;
    }

    private static boolean hasJsonAnySetter(Class<?> clz) {
        for (Method m : clz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(JsonAnySetter.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean javaAllowsAdditional(Class<?> clz) {
        if (hasJsonAnySetter(clz)) {
            return true;
        }
        JsonIgnoreProperties annot = clz.getAnnotation(JsonIgnoreProperties.class);
        return annot != null && annot.ignoreUnknown();
    }

    private static Path schemaFile() {
        // Resolve docs/yaml-schema.json by walking up from the current working directory until
        // the docs/ folder appears. Maven runs the test with cwd = api/ module dir; the schema
        // lives at <repo>/docs/yaml-schema.json.
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve("docs").resolve("yaml-schema.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
            if (dir == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate docs/yaml-schema.json from cwd");
    }
}
