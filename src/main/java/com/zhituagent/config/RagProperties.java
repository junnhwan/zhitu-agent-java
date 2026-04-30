package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.rag")
public class RagProperties {

    private boolean hybridEnabled = true;
    private int lexicalTopK = 10;
    private double minAcceptedScore = 0.15;

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public int getLexicalTopK() {
        return lexicalTopK;
    }

    public void setLexicalTopK(int lexicalTopK) {
        this.lexicalTopK = lexicalTopK;
    }

    public double getMinAcceptedScore() {
        return minAcceptedScore;
    }

    public void setMinAcceptedScore(double minAcceptedScore) {
        this.minAcceptedScore = minAcceptedScore;
    }
}
