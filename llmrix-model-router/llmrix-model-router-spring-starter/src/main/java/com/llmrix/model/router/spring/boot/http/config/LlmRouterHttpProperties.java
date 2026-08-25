package com.llmrix.model.router.spring.boot.http.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@ConfigurationProperties("llmrix.model.router.http")
@Getter
@Setter
public class LlmRouterHttpProperties {
    private boolean enabled;
    private Auth auth = new Auth();

    @Getter
    @Setter
    public static class Auth {
        private String mode = "api-key";
        private String bootstrapKey;

        public void setMode(String mode) {
            if (mode == null)
                throw new IllegalArgumentException("llmrix.model.router.http.auth.mode must not be null");
            String normalized = mode.strip().toLowerCase(Locale.ROOT);
            if (!normalized.equals("api-key") && !normalized.equals("none")) {
                throw new IllegalArgumentException(
                        "llmrix.model.router.http.auth.mode must be api-key or none");
            }
            this.mode = normalized;
        }
    }
}
