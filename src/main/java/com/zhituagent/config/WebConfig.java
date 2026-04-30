package com.zhituagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
        PgVectorProperties.class,
        InfrastructureProperties.class,
        EvalProperties.class,
        TraceArchiveProperties.class
})
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
