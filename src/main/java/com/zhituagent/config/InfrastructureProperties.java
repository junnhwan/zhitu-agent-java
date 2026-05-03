package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.infrastructure")
public class InfrastructureProperties {

    private boolean redisEnabled = false;
    private boolean elasticsearchEnabled = false;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public boolean isElasticsearchEnabled() {
        return elasticsearchEnabled;
    }

    public void setElasticsearchEnabled(boolean elasticsearchEnabled) {
        this.elasticsearchEnabled = elasticsearchEnabled;
    }
}
