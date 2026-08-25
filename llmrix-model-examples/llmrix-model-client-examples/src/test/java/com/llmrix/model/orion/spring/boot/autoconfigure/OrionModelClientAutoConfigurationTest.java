package com.llmrix.model.orion.spring.boot.autoconfigure;

import com.llmrix.model.orion.client.OrionModelClient;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.video.VideoModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OrionModelClientAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrionModelClientAutoConfiguration.class));

    @Test
    void createsClientWithoutDefaultModel() {
        contextRunner.withPropertyValues("llmrix.model.orion.base-url=http://localhost:9999/v1")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrionModelClient.class);
                    assertThat(context).doesNotHaveBean("orionModelChatModel");
                });
    }

    @Test
    void createsCoreModelForDefaultModel() {
        contextRunner.withPropertyValues(
                        "llmrix.model.orion.base-url=http://localhost:9999/v1",
                        "llmrix.model.orion.default-model=general")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrionModelClient.class);
                    assertThat(context.getBean("orionModelChatModel")).isInstanceOf(ChatModel.class);
        });
    }

    @Test
    void createsTypedOperationModelsForConfiguredDefaults() {
        contextRunner.withPropertyValues(
                        "llmrix.model.orion.base-url=http://localhost:9999/v1",
                        "llmrix.model.orion.defaults.chat=general",
                        "llmrix.model.orion.defaults.embedding=embedding",
                        "llmrix.model.orion.defaults.audio=audio",
                        "llmrix.model.orion.defaults.image=image",
                        "llmrix.model.orion.defaults.video=video")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrionModelClient.class);
                    assertThat(context).hasBean("orionModelChatModel");
                    assertThat(context).hasBean("orionModelEmbeddingModel");
                    assertThat(context).hasBean("orionModelAudioModel");
                    assertThat(context).hasBean("orionModelImageModel");
                    assertThat(context).hasBean("orionModelVideoModel");
                    assertThat(context.getBean("orionModelEmbeddingModel")).isInstanceOf(EmbeddingModel.class);
                    assertThat(context.getBean("orionModelAudioModel")).isInstanceOf(AudioModel.class);
                    assertThat(context.getBean("orionModelImageModel")).isInstanceOf(ImageModel.class);
                    assertThat(context.getBean("orionModelVideoModel")).isInstanceOf(VideoModel.class);
                });
    }

    @Test
    void disabledPropertySkipsClient() {
        contextRunner.withPropertyValues("llmrix.model.orion.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrionModelClient.class));
    }
}
