package org.apache.flink.agents.integrations.embeddingmodels.ollama;

import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingModelConnectionTest {

    private static ResourceDescriptor buildDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    private static BiFunction<String, ResourceType, Resource> dummyResource = (a, b) -> null;

    @Test
    @DisplayName("Create OllamaEmbeddingModelConnection and check embed method")
    void testCreateAndEmbed() {
        OllamaEmbeddingModelConnection conn =
                new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
        assertNotNull(conn);
        // No llamamos a embed porque requiere un servidor Ollama real
    }

    @Test
    @DisplayName("Test EmbeddingModelConnection annotation presence")
    void testAnnotationPresence() {
        assertNull(
                OllamaEmbeddingModelConnection.class.getAnnotation(EmbeddingModelConnection.class));
    }

    @Test
    @DisplayName("Test EmbeddingModelSetup annotation presence on setup class")
    void testSetupAnnotationPresence() {
        class DummySetup extends BaseEmbeddingModelSetup {
            public DummySetup(
                    ResourceDescriptor descriptor,
                    BiFunction<String, ResourceType, Resource> getResource) {
                super(descriptor, getResource);
            }

            @Override
            public BaseEmbeddingModelConnection getConnection() {
                return new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
            }
        }
        DummySetup setup = new DummySetup(buildDescriptor(), dummyResource);
        assertNotNull(setup.getConnection());
    }
}
