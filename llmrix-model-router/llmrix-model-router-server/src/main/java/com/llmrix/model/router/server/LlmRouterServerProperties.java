package com.llmrix.model.router.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Locale;

@ConfigurationProperties("llmrix.model.router.server")
public class LlmRouterServerProperties {
    private boolean enabled;
    private Auth auth = new Auth();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public static class Auth {
        private String mode = "api-key";
        private String bootstrapKey;
        public String getMode() { return mode; }
        public void setMode(String mode) {
            if (mode == null) throw new IllegalArgumentException("llmrix.model.router.server.auth.mode must not be null");
            String normalized = mode.strip().toLowerCase(Locale.ROOT);
            if (!normalized.equals("api-key") && !normalized.equals("none")) {
                throw new IllegalArgumentException(
                        "llmrix.model.router.server.auth.mode must be api-key or none");
            }
            this.mode = normalized;
        }
        public String getBootstrapKey() { return bootstrapKey; }
        public void setBootstrapKey(String bootstrapKey) { this.bootstrapKey = bootstrapKey; }
    }
}
