package com.videotrace.utils;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelProviderConfigurationTest {
    @Test void llmCredentialsCannotLeakIntoEmbeddingOrAsrConfiguration() throws Exception {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("application", properties));
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "LLM_API_KEY", "official-key", "LLM_BASE_URL", "https://api.deepseek.com",
                "SILICONFLOW_API_KEY", "silicon-key", "SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")));
        assertEquals("official-key", env.getProperty("ai.deepseek.api-key"));
        assertEquals("https://api.deepseek.com", env.getProperty("ai.deepseek.base-url"));
        for (Class<?> client : List.of(EmbeddingUtils.class, AliyunAsrUtils.class)) {
            String binding = client.getConstructors()[0].getParameters()[0].getAnnotation(Value.class).value();
            assertEquals("silicon-key", env.resolveRequiredPlaceholders(binding));
        }
        assertEquals("https://api.siliconflow.cn/v1", env.getProperty("ai.embedding.base-url"));
    }
}
