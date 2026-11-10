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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.AudioFormat;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.VideoFormat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BedrockMultimodalTest {

    private static final String S3 = "s3://my-bucket/cat.png";
    private static final String BASE64 = "aGVsbG8="; // "hello"
    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

    private static List<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock> content(
            ChatMessage message) {
        List<Message> messages = BedrockChatUtils.mergeMessages(List.of(message));
        assertThat(messages).hasSize(1);
        return messages.get(0).content();
    }

    @Test
    @DisplayName("A text-only user message is sent as before, one text block per TextBlock")
    void testTextOnlyUserMessageIsUnchanged() {
        var content = content(ChatMessage.user(List.of(TextBlock.of("hi"))));

        assertThat(content).hasSize(1);
        assertThat(content.get(0).text()).isEqualTo("hi");
    }

    @Test
    @DisplayName("Base64 media becomes Converse blocks with bytes and a format, in block order")
    void testBase64MediaBecomesBytes() {
        var content =
                content(
                        ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare"),
                                        ImageBlock.fromBase64("image/png", BASE64),
                                        DocumentBlock.fromBase64("application/pdf", BASE64),
                                        VideoBlock.fromBase64("video/mp4", BASE64),
                                        AudioBlock.fromBase64("audio/mpeg", BASE64))));

        assertThat(content).hasSize(5);
        assertThat(content.get(0).text()).isEqualTo("Compare");
        assertThat(content.get(1).image().format()).isEqualTo(ImageFormat.PNG);
        assertThat(content.get(1).image().source().bytes().asByteArray()).isEqualTo(HELLO);
        assertThat(content.get(2).document().format()).isEqualTo(DocumentFormat.PDF);
        assertThat(content.get(2).document().source().bytes().asByteArray()).isEqualTo(HELLO);
        assertThat(content.get(3).video().format()).isEqualTo(VideoFormat.MP4);
        assertThat(content.get(3).video().source().bytes().asByteArray()).isEqualTo(HELLO);
        assertThat(content.get(4).audio().format()).isEqualTo(AudioFormat.MP3);
        assertThat(content.get(4).audio().source().bytes().asByteArray()).isEqualTo(HELLO);
    }

    @Test
    @DisplayName("s3:// URLs become S3 locations")
    void testS3UrlsBecomeS3Locations() {
        var content =
                content(
                        ChatMessage.user(
                                List.of(
                                        ImageBlock.fromUrl("image/jpeg", S3),
                                        DocumentBlock.fromUrl(
                                                "text/markdown", "s3://my-bucket/notes.md"),
                                        VideoBlock.fromUrl("video/quicktime", "s3://my-bucket/v"),
                                        AudioBlock.fromUrl("audio/wav", "s3://my-bucket/a.wav"))));

        assertThat(content.get(0).image().format()).isEqualTo(ImageFormat.JPEG);
        assertThat(content.get(0).image().source().s3Location().uri()).isEqualTo(S3);
        assertThat(content.get(0).image().source().bytes()).isNull();
        assertThat(content.get(1).document().format()).isEqualTo(DocumentFormat.MD);
        assertThat(content.get(1).document().source().s3Location().uri())
                .isEqualTo("s3://my-bucket/notes.md");
        assertThat(content.get(2).video().format()).isEqualTo(VideoFormat.MOV);
        assertThat(content.get(2).video().source().s3Location().uri())
                .isEqualTo("s3://my-bucket/v");
        assertThat(content.get(3).audio().format()).isEqualTo(AudioFormat.WAV);
        assertThat(content.get(3).audio().source().s3Location().uri())
                .isEqualTo("s3://my-bucket/a.wav");
    }

    @Test
    @DisplayName("Media types are matched without case or parameters")
    void testMediaTypeParametersAreIgnored() {
        var content =
                content(
                        ChatMessage.user(
                                List.of(
                                        DocumentBlock.fromBase64(
                                                "Text/Plain; charset=utf-8", BASE64))));

        assertThat(content.get(0).document().format()).isEqualTo(DocumentFormat.TXT);
    }

    @Test
    @DisplayName("Document names keep only characters Converse allows, else document-<n>")
    void testDocumentNames() {
        var content =
                content(
                        ChatMessage.user(
                                List.of(
                                        new DocumentBlock(
                                                "application/pdf",
                                                new Base64Source(BASE64),
                                                "Q3  report (final).pdf",
                                                null,
                                                null),
                                        DocumentBlock.fromBase64("application/pdf", BASE64),
                                        new DocumentBlock(
                                                "application/pdf",
                                                new Base64Source(BASE64),
                                                "...",
                                                null,
                                                null))));

        assertThat(content.get(0).document().name()).isEqualTo("Q3 report (final) pdf");
        assertThat(content.get(1).document().name()).isEqualTo("document-2");
        assertThat(content.get(2).document().name()).isEqualTo("document-3");
        assertThat(BedrockChatUtils.documentName("a".repeat(250), 1)).hasSize(200);
    }

    @Test
    @DisplayName("Other URLs and media types without a Converse format fail without leaking")
    void testUnsupportedSourcesAndTypesFail() {
        for (ContentBlock block :
                List.<ContentBlock>of(
                        ImageBlock.fromUrl("image/png", "https://example.com/cat.png?sig=secret"),
                        ImageBlock.fromBase64("image/bmp", BASE64),
                        AudioBlock.fromBase64("audio/midi", BASE64))) {
            assertThatThrownBy(() -> content(ChatMessage.user(List.of(block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("Amazon Bedrock cannot send a")
                    .hasMessageContaining(" " + block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @Test
    @DisplayName("Media in assistant messages and tool results fails explicitly")
    void testMediaOutsideUserMessagesFails() {
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", S3)));
        ChatMessage tool =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", S3)),
                                false));

        for (ChatMessage message : List.of(assistant, tool)) {
            assertThatThrownBy(() -> BedrockChatUtils.mergeMessages(List.of(message)))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageContaining(
                            "only user messages can carry media, not "
                                    + message.getRole().getValue()
                                    + " messages");
        }
    }

    @Test
    @DisplayName("chat() surfaces media errors with their own types, before any request")
    void testChatKeepsMediaErrorTypes() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelConnection.class.getName())
                        .addInitialArgument("region", "us-east-1")
                        .addInitialArgument("model", "us.anthropic.claude-sonnet-4-20250514-v1:0")
                        .build();
        BedrockChatModelConnection connection =
                new BedrockChatModelConnection(
                        descriptor, TestResourceContexts.fromGetResource((a, b) -> null));
        ChatMessage httpsImage =
                ChatMessage.user(List.of(ImageBlock.fromUrl("image/png", "https://example.com/a")));
        ChatMessage malformed =
                ChatMessage.user(List.of(ImageBlock.fromBase64("image/png", "not base64!")));

        assertThatThrownBy(() -> connection.chat(List.of(httpsImage), List.of(), Map.of()))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
        assertThatThrownBy(() -> connection.chat(List.of(malformed), List.of(), Map.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
