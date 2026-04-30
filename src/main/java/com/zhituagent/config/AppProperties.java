package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.app")
public class AppProperties {

    private String systemPromptLocation = "classpath:system-prompt/chat-agent.txt";
    private boolean reactEnabled = false;
    private int reactMaxIters = 4;

    public String getSystemPromptLocation() {
        return systemPromptLocation;
    }

    public void setSystemPromptLocation(String systemPromptLocation) {
        this.systemPromptLocation = systemPromptLocation;
    }

    public boolean isReactEnabled() {
        return reactEnabled;
    }

    public void setReactEnabled(boolean reactEnabled) {
        this.reactEnabled = reactEnabled;
    }

    public int getReactMaxIters() {
        return reactMaxIters;
    }

    public void setReactMaxIters(int reactMaxIters) {
        this.reactMaxIters = reactMaxIters;
    }
}
