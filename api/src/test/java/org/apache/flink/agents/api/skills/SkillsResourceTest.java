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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsResourceTest {

    @Test
    void fromLocalDirEmitsLocalScheme() {
        Skills skills = Skills.fromLocalDir("/tmp/a", "/tmp/b");
        assertEquals(
                List.of(
                        new SkillSourceSpec("local", Map.of("path", "/tmp/a")),
                        new SkillSourceSpec("local", Map.of("path", "/tmp/b"))),
                skills.getSources());
        assertEquals(ResourceType.SKILLS, skills.getResourceType());
    }

    @Test
    void fromUrlEmitsUrlScheme() {
        Skills skills = Skills.fromUrl("https://example.com/x.zip");
        assertEquals(
                List.of(new SkillSourceSpec("url", Map.of("url", "https://example.com/x.zip"))),
                skills.getSources());
    }

    @Test
    void fromUrlAcceptsSharedValidHostSyntax() {
        for (String url :
                List.of(
                        "https://localhost/x.zip",
                        "https://127.0.0.1/x.zip",
                        "https://[::1]/x.zip",
                        "https://example.com./x.zip",
                        "https://example.com:/x.zip",
                        "https://example.com:65535/x.zip",
                        "https://999/x.zip",
                        "https://1bar/x.zip",
                        "https://999./x.zip")) {
            assertEquals(url, Skills.fromUrl(url).getSources().get(0).getParams().get("url"));
        }
    }

    @Test
    void fromUrlRejectsScopedIpv6() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Skills.fromUrl("https://[fe80::1%25lo0]/x.zip"));
        assertTrue(ex.getMessage().contains("must not include an IPv6 zone identifier"));
        assertNull(ex.getCause());
    }

    @Test
    void fromUrlWithSha256EmitsIntegrityParam() {
        String digest = "A".repeat(64);
        Skills skills = Skills.fromUrlWithSha256("https://example.com/x.zip", digest);
        assertEquals(
                List.of(
                        new SkillSourceSpec(
                                "url",
                                Map.of("url", "https://example.com/x.zip", "sha256", digest))),
                skills.getSources());
    }

    @Test
    void fromUrlUnsafeRequiresExplicitParam() {
        Skills skills = Skills.fromUrlUnsafe("http://example.com/x.zip");
        assertEquals("true", skills.getSources().get(0).getParams().get("allow_insecure_http"));
    }

    @Test
    void fromUrlUnsafeWithSha256EmitsBothParams() {
        String digest = "a".repeat(64);
        Skills skills = Skills.fromUrlUnsafeWithSha256("http://example.com/x.zip", digest);
        assertEquals(
                Map.of(
                        "url",
                        "http://example.com/x.zip",
                        "sha256",
                        digest,
                        "allow_insecure_http",
                        "true"),
                skills.getSources().get(0).getParams());
    }

    @Test
    void fromUrlRejectsPlainHttpByDefault() {
        assertThrows(
                IllegalArgumentException.class, () -> Skills.fromUrl("http://example.com/x.zip"));
    }

    @Test
    void fromUrlWithSha256RejectsMalformedDigest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Skills.fromUrlWithSha256("https://example.com/x.zip", "invalid"));
    }

    @Test
    void fromUrlRejectsUnsupportedSchemeClearly() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                Skills.fromUrl(
                                        "ftp://user:password@example.com/x.zip?token=secret#part"));
        assertEquals(
                "Only HTTP(S) skill URLs are supported: ftp://example.com/x.zip", ex.getMessage());
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test
    void fromUrlRejectsMalformedUrl() {
        IllegalArgumentException unparseable =
                assertThrows(
                        IllegalArgumentException.class, () -> Skills.fromUrl("https://[::1/x.zip"));
        assertEquals("Invalid skill URL: <redacted>", unparseable.getMessage());

        IllegalArgumentException unsafeForLogs =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Skills.fromUrl("https://u:pw@example.com/a\nheader?token=SECRET"));
        assertEquals("Invalid skill URL: <redacted>", unsafeForLogs.getMessage());
        assertFalse(unsafeForLogs.getMessage().contains("pw"));
        assertFalse(unsafeForLogs.getMessage().contains("SECRET"));

        for (String url :
                List.of("https://example.com/skills[1].zip", "https://[fe80::1%eth0]/x.zip")) {
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> Skills.fromUrl(url), url);
            assertEquals("Invalid skill URL: " + url, ex.getMessage());
        }
    }

    @Test
    void fromUrlRedactsMalformedUrlWithoutLosingSafeContext() {
        for (String path : List.of("a b.zip", "%zz")) {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Skills.fromUrl(
                                            "https://u:pw@example.com/"
                                                    + path
                                                    + "?token=SECRET#fragment"));
            assertEquals("Invalid skill URL: https://example.com/" + path, ex.getMessage());
            assertFalse(ex.getMessage().contains("pw"));
            assertFalse(ex.getMessage().contains("SECRET"));
        }
    }

    @Test
    void fromUrlAcceptsBracketsOutsideRawPath() {
        for (String url :
                List.of(
                        "https://example.com/skills%5B1%5D.zip",
                        "https://example.com/x.zip?a[0]=1", "https://example.com/x.zip#f[1]")) {
            assertEquals(url, Skills.fromUrl(url).getSources().get(0).getParams().get("url"));
        }
    }

    @Test
    void fromUrlRejectsInvalidHostAndPort() {
        for (String url :
                List.of(
                        "https://example.com:bad/x.zip?token=top-secret",
                        "https://user:supersecret/x.zip?token=TOPSECRET",
                        "https://user:sup:ersecret/x.zip?token=TOPSECRET",
                        "https://user:999999/x.zip?token=TOPSECRET")) {
            IllegalArgumentException malformedPort =
                    assertThrows(IllegalArgumentException.class, () -> Skills.fromUrl(url), url);
            assertNull(malformedPort.getCause());
            assertTrue(malformedPort.getMessage().endsWith("<redacted>"));
            assertFalse(malformedPort.getMessage().contains("supersecret"));
            assertFalse(malformedPort.getMessage().contains("TOPSECRET"));
            assertFalse(malformedPort.getMessage().contains("top-secret"));
        }

        for (String url : List.of("https://:443/x.zip", "https://example.com:65536/x.zip")) {
            assertThrows(IllegalArgumentException.class, () -> Skills.fromUrl(url), url);
        }
    }

    @Test
    void fromUrlRejectsInvalidHostnameSyntax() {
        for (String url :
                List.of(
                        "https://exa_mple.com/x.zip",
                        "https://tést.com/x.zip",
                        "https://%65xample.com/x.zip",
                        "https://-example.com/x.zip",
                        "https://example-.com/x.zip",
                        "https://.example.com/x.zip",
                        "https://example..com/x.zip",
                        "https://a../x.zip",
                        "https://../x.zip",
                        "https://999.999.999.999/x.zip",
                        "https://127.1/x.zip",
                        "https://1.2.3/x.zip",
                        "https://foo.123/x.zip",
                        "https://foo.1bar/x.zip",
                        "https://1.2.3.4.5/x.zip",
                        "https://1.2.3./x.zip",
                        "https://1.2.3.4./x.zip",
                        "https://[v1.foo]/x.zip")) {
            assertThrows(IllegalArgumentException.class, () -> Skills.fromUrl(url), url);
        }
    }

    @Test
    void fromUrlRejectsCompatibilitySensitiveHosts() {
        for (String url :
                List.of(
                        "https://skill_server/x.zip",
                        "https://tést.com/x.zip",
                        "https://\u212A.com/x.zip")) {
            assertThrows(IllegalArgumentException.class, () -> Skills.fromUrl(url), url);
        }
    }

    @Test
    void fromUrlRejectsUserInfoWithoutLeakingSecrets() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                Skills.fromUrl(
                                        "https://user:password@example.com/x.zip?token=secret"));
        assertTrue(ex.getMessage().contains("must not include user info"));
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test
    void fromUrlRedactsOpaqueMalformedCredentials() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Skills.fromUrl("https:user:password?token=top-secret"));
        assertTrue(ex.getMessage().endsWith("<redacted>"));
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("top-secret"));
    }

    @Test
    void fromClasspathEmitsClasspathScheme() {
        Skills skills = Skills.fromClasspath("skills");
        assertEquals(
                List.of(new SkillSourceSpec("classpath", Map.of("resource", "skills"))),
                skills.getSources());
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        Skills original = Skills.fromLocalDir("/tmp/skill1", "/tmp/skill2");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        Skills restored = mapper.readValue(json, Skills.class);
        assertEquals(original.getSources(), restored.getSources());
    }

    @Test
    void unsafePinnedUrlRoundTripsThroughJackson() throws Exception {
        Skills original =
                Skills.fromUrlUnsafeWithSha256("http://example.com/skills.zip", "a".repeat(64));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        JsonNode allowInsecureHttp =
                mapper.readTree(json).at("/sources/0/params/allow_insecure_http");
        assertTrue(allowInsecureHttp.isTextual());
        assertEquals("true", allowInsecureHttp.asText());
        Skills restored = mapper.readValue(json, Skills.class);
        assertEquals(original.getSources(), restored.getSources());
    }

    @Test
    void reservedNamesMatchPython() {
        assertEquals("_skills_config", Skills.SKILLS_CONFIG);
        assertEquals("load_skill", Skills.LOAD_SKILL_TOOL);
        assertEquals("bash", Skills.BASH_TOOL);
    }
}
