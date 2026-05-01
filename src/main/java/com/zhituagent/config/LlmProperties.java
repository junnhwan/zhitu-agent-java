package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.llm")
public class LlmProperties {

    private boolean mockMode = true;
    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "mock-agent";
    private final RateLimit rateLimit = new RateLimit();

    public boolean isMockMode() {
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class RateLimit {

        private boolean enabled = false;
        private int limitForPeriod = 48;
        private long limitRefreshPeriodSeconds = 60;
        private long timeoutSeconds = 120;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public long getLimitRefreshPeriodSeconds() {
            return limitRefreshPeriodSeconds;
        }

        public void setLimitRefreshPeriodSeconds(long limitRefreshPeriodSeconds) {
            this.limitRefreshPeriodSeconds = limitRefreshPeriodSeconds;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
