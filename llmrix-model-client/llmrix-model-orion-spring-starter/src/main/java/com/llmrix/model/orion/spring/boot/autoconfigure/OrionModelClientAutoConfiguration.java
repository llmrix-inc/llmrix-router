package com.llmrix.model.orion.spring.boot.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.orion.client.OrionModelClient;
import com.llmrix.model.orion.observation.OrionModelClientListener;
import com.llmrix.model.orion.spring.boot.observability.MicrometerOrionModelClientListener;
import com.llmrix.model.orion.spring.boot.properties.OrionModelClientProperties;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.video.VideoModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@AutoConfiguration
@ConditionalOnClass(OrionModelClient.class)
@ConditionalOnProperty(prefix = "llmrix.model.orion", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OrionModelClientProperties.class)
public class OrionModelClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OrionModelClient orionModelClient(
            OrionModelClientProperties properties,
            ObjectProvider<HttpClient> httpClient,
            ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<OrionModelClientListener> listener) {
        OrionModelClient.Builder builder = OrionModelClient.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .defaultModel(chatDefault(properties))
                .defaultEmbeddingModel(properties.getDefaults().getEmbedding())
                .defaultAudioModel(properties.getDefaults().getAudio())
                .defaultImageModel(properties.getDefaults().getImage())
                .defaultVideoModel(properties.getDefaults().getVideo())
                .connectTimeout(properties.getConnectTimeout())
                .timeout(properties.getTimeout())
                .headers(properties.getHeaders())
                .listener(listener.getIfAvailable(() -> OrionModelClientListener.NOOP));
        HttpClient configuredHttpClient = httpClient.getIfAvailable();
        if (configuredHttpClient != null) builder.httpClient(configuredHttpClient);
        ObjectMapper configuredObjectMapper = objectMapper.getIfAvailable();
        if (configuredObjectMapper != null) builder.objectMapper(configuredObjectMapper);
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({io.micrometer.observation.ObservationRegistry.class,
            io.micrometer.core.instrument.MeterRegistry.class})
    static class MicrometerConfiguration {
        @Bean
        @ConditionalOnMissingBean(OrionModelClientListener.class)
        OrionModelClientListener orionModelClientListener(
                ObjectProvider<io.micrometer.observation.ObservationRegistry> observations,
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
            return new MicrometerOrionModelClientListener(
                    observations.getIfAvailable(() -> io.micrometer.observation.ObservationRegistry.NOOP),
                    meters.getIfAvailable());
        }
    }

    @Bean(name = "orionModelChatModel")
    @ConditionalOnMissingBean(name = "orionModelChatModel")
    @ConditionalOnExpression("'${llmrix.model.orion.default-model:}' != '' || "
            + "'${llmrix.model.orion.defaults.chat:}' != ''")
    ChatModel orionModelChatModel(OrionModelClient client) {
        return client.defaultChatModel();
    }

    @Bean(name = "orionModelEmbeddingModel")
    @ConditionalOnMissingBean(name = "orionModelEmbeddingModel")
    @ConditionalOnProperty(prefix = "llmrix.model.orion.defaults", name = "embedding")
    EmbeddingModel orionModelEmbeddingModel(OrionModelClient client) {
        return client.defaultEmbeddingModel();
    }

    @Bean(name = "orionModelAudioModel")
    @ConditionalOnMissingBean(name = "orionModelAudioModel")
    @ConditionalOnProperty(prefix = "llmrix.model.orion.defaults", name = "audio")
    AudioModel orionModelAudioModel(OrionModelClient client) {
        return client.defaultAudioModel();
    }

    @Bean(name = "orionModelImageModel")
    @ConditionalOnMissingBean(name = "orionModelImageModel")
    @ConditionalOnProperty(prefix = "llmrix.model.orion.defaults", name = "image")
    ImageModel orionModelImageModel(OrionModelClient client) {
        return client.defaultImageModel();
    }

    @Bean(name = "orionModelVideoModel")
    @ConditionalOnMissingBean(name = "orionModelVideoModel")
    @ConditionalOnProperty(prefix = "llmrix.model.orion.defaults", name = "video")
    VideoModel orionModelVideoModel(OrionModelClient client) {
        return client.defaultVideoModel();
    }

    private static String chatDefault(OrionModelClientProperties properties) {
        String chat = properties.getDefaults().getChat();
        return chat == null || chat.isBlank() ? properties.getDefaultModel() : chat;
    }

}
