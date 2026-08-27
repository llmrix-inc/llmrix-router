package com.llmrix.model.router.integrations.provider;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelProviderTest {

    @Test
    void openRouterExposesEmbeddingAndRerankAdapters() {
        ModelProviderRequest request = new ModelProviderRequest(
                "openrouter", "nvidia/nemotron-3-embed-1b:free",
                "https://openrouter.ai/api/v1", RequestAuthenticator.NONE,
                Map.of(), Map.of());

        ModelClient client = OpenAiCompatibleModelProvider.openRouter().create(request);

        assertThat(client.chat()).isPresent();
        assertThat(client.embeddings()).isPresent();
        assertThat(client.rerank()).isPresent();
    }
}
