package com.zhituagent.config;

import com.zhituagent.mcp.McpClient;
import com.zhituagent.mcp.McpProperties;
import com.zhituagent.mcp.MockMcpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({
        AppProperties.class,
        LlmProperties.class,
        EmbeddingProperties.class,
        RagProperties.class,
        RerankProperties.class,
        EsProperties.class,
        InfrastructureProperties.class,
        EvalProperties.class,
        TraceArchiveProperties.class,
        McpProperties.class
})
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * Provide a {@link MockMcpClient} bean when MCP is enabled and the user has
     * not wired their own client (e.g. a stdio/SSE-backed real client). Lets
     * {@code zhitu.mcp.enabled=true} immediately surface the demo tools without
     * additional wiring.
     */
    @Bean
    @ConditionalOnProperty(prefix = "zhitu.mcp", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(McpClient.class)
    public McpClient mockMcpClient() {
        return new MockMcpClient();
    }
}
