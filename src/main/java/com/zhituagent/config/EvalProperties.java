package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "zhitu.eval")
public class EvalProperties {

    private boolean enabled = false;
    private boolean exitAfterRun = false;
    private String fixtureResource = "eval/baseline-chat-cases.jsonl";
    private String reportDir = "target/eval-reports";
    private List<String> modes = new ArrayList<>(List.of("dense", "dense-rerank", "hybrid-rerank"));
    private String label = "";
    private List<String> compareLabels = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExitAfterRun() {
        return exitAfterRun;
    }

    public void setExitAfterRun(boolean exitAfterRun) {
        this.exitAfterRun = exitAfterRun;
    }

    public String getFixtureResource() {
        return fixtureResource;
    }

    public void setFixtureResource(String fixtureResource) {
        this.fixtureResource = fixtureResource;
    }

    public String getReportDir() {
        return reportDir;
    }

    public void setReportDir(String reportDir) {
        this.reportDir = reportDir;
    }

    public List<String> getModes() {
        return modes;
    }

    public void setModes(List<String> modes) {
        this.modes = modes == null ? new ArrayList<>() : new ArrayList<>(modes);
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public List<String> getCompareLabels() {
        return compareLabels;
    }

    public void setCompareLabels(List<String> compareLabels) {
        this.compareLabels = compareLabels == null ? new ArrayList<>() : new ArrayList<>(compareLabels);
    }
}
