package com.zhituagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class BaselineComparisonReporter {

    private static final Logger log = LoggerFactory.getLogger(BaselineComparisonReporter.class);

    private final ObjectMapper objectMapper;

    public BaselineComparisonReporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void compareLatest(Path reportDir,
                              List<String> labels,
                              Path jsonOutput,
                              Path markdownOutput) throws IOException {
        if (labels == null || labels.size() < 2) {
            throw new IllegalArgumentException("compareLabels must contain at least 2 labels");
        }
        Map<String, BaselineEvalComparisonReport> reportsByLabel = new LinkedHashMap<>();
        for (String label : labels) {
            Path latest = findLatestReport(reportDir, label)
                    .orElseThrow(() -> new IllegalStateException("no report found for label=" + label + " under " + reportDir));
            BaselineEvalComparisonReport parsed = objectMapper.readValue(latest.toFile(), BaselineEvalComparisonReport.class);
            reportsByLabel.put(label, parsed);
            log.info("loaded report label={} path={}", label, latest);
        }

        ComparisonOutput output = buildComparison(labels, reportsByLabel);
        writeJson(output, jsonOutput);
        writeMarkdown(output, markdownOutput);
    }

    Optional<Path> findLatestReport(Path reportDir, String label) throws IOException {
        if (!Files.isDirectory(reportDir)) {
            return Optional.empty();
        }
        String prefix = "baseline-" + label + "-";
        try (Stream<Path> entries = Files.list(reportDir)) {
            return entries
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".json");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        }
    }

    ComparisonOutput buildComparison(List<String> labels,
                                     Map<String, BaselineEvalComparisonReport> reportsByLabel) {
        List<String> allModes = labels.stream()
                .flatMap(label -> reportsByLabel.get(label).requestedModes().stream())
                .distinct()
                .toList();

        List<ModeComparison> modeComparisons = new ArrayList<>();
        for (String mode : allModes) {
            Map<String, BaselineEvalResult> resultByLabel = new LinkedHashMap<>();
            for (String label : labels) {
                BaselineEvalComparisonReport report = reportsByLabel.get(label);
                report.modeReports().stream()
                        .filter(r -> Objects.equals(r.mode(), mode))
                        .findFirst()
                        .ifPresent(r -> resultByLabel.put(label, r));
            }
            if (resultByLabel.size() < 2) {
                log.warn("skipping mode={} not present in all labels={}", mode, resultByLabel.keySet());
                continue;
            }
            modeComparisons.add(new ModeComparison(mode, resultByLabel, buildCaseDiffs(resultByLabel)));
        }

        return new ComparisonOutput(
                OffsetDateTime.now().toString(),
                List.copyOf(labels),
                List.copyOf(modeComparisons)
        );
    }

    private List<CaseDiff> buildCaseDiffs(Map<String, BaselineEvalResult> resultByLabel) {
        Map<String, Map<String, BaselineEvalResult.CaseResult>> byCaseAndLabel = new LinkedHashMap<>();
        List<String> caseOrder = new ArrayList<>();
        for (Map.Entry<String, BaselineEvalResult> entry : resultByLabel.entrySet()) {
            for (BaselineEvalResult.CaseResult caseResult : entry.getValue().results()) {
                if (!byCaseAndLabel.containsKey(caseResult.caseId())) {
                    caseOrder.add(caseResult.caseId());
                }
                byCaseAndLabel.computeIfAbsent(caseResult.caseId(), k -> new LinkedHashMap<>())
                        .put(entry.getKey(), caseResult);
            }
        }

        List<CaseDiff> diffs = new ArrayList<>();
        for (String caseId : caseOrder) {
            Map<String, BaselineEvalResult.CaseResult> perLabel = byCaseAndLabel.get(caseId);
            if (perLabel.size() != resultByLabel.size()) {
                continue;
            }
            BaselineEvalResult.CaseResult any = perLabel.values().iterator().next();
            diffs.add(new CaseDiff(
                    caseId,
                    any.type(),
                    any.splitMode(),
                    perLabel
            ));
        }
        return diffs;
    }

    private void writeJson(ComparisonOutput output, Path jsonOutput) throws IOException {
        Files.createDirectories(jsonOutput.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonOutput.toFile(), output);
    }

    private void writeMarkdown(ComparisonOutput output, Path markdownOutput) throws IOException {
        Files.createDirectories(markdownOutput.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Baseline Comparison: ").append(String.join(" vs ", output.labels())).append("\n\n");
        sb.append("Generated: ").append(output.generatedAt()).append("\n\n");

        for (ModeComparison modeComparison : output.modes()) {
            sb.append("## Mode: `").append(modeComparison.mode()).append("`\n\n");
            sb.append(buildAggregateTable(output.labels(), modeComparison));
            sb.append("\n");
            sb.append(buildSplitTable(output.labels(), modeComparison, "trainSplit"));
            sb.append("\n");
            sb.append(buildSplitTable(output.labels(), modeComparison, "evalSplit"));
            sb.append("\n");
            sb.append(buildPerCaseTable(output.labels(), modeComparison));
            sb.append("\n");
        }

        Files.writeString(markdownOutput, sb.toString(), StandardCharsets.UTF_8);
    }

    private String buildAggregateTable(List<String> labels, ModeComparison modeComparison) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Aggregate\n\n");
        sb.append("| 指标 |");
        for (String label : labels) {
            sb.append(" ").append(label).append(" |");
        }
        if (labels.size() == 2) {
            sb.append(" Δ (").append(labels.get(1)).append(" - ").append(labels.get(0)).append(") |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < labels.size(); i++) {
            sb.append("---|");
        }
        if (labels.size() == 2) {
            sb.append("---|");
        }
        sb.append("\n");

        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "通过率",
                r -> String.format("%d/%d (%.1f%%)", r.passedCases(), r.totalCases(), 100.0 * r.passedCases() / Math.max(1, r.totalCases())),
                r -> (double) r.passedCases() / Math.max(1, r.totalCases()), "%.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "routeAccuracy",
                r -> String.format("%.3f", r.routeAccuracy()), BaselineEvalResult::routeAccuracy, "%+.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "meanRecallAt5",
                r -> String.format("%.3f", r.meanRecallAt5()), BaselineEvalResult::meanRecallAt5, "%+.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "meanMrrAt5",
                r -> String.format("%.3f", r.meanMrrAt5()), BaselineEvalResult::meanMrrAt5, "%+.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "meanNdcgAt5",
                r -> String.format("%.3f", r.meanNdcgAt5()), BaselineEvalResult::meanNdcgAt5, "%+.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "meanAnswerKeywordCoverage",
                r -> String.format("%.3f", r.meanAnswerKeywordCoverage()), BaselineEvalResult::meanAnswerKeywordCoverage, "%+.3f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "avgLatencyMs",
                r -> String.format("%.1f", r.averageLatencyMs()), BaselineEvalResult::averageLatencyMs, "%+.1f");
        appendAggregateRow(sb, labels, modeComparison.resultByLabel(), "p90LatencyMs",
                r -> String.format("%.1f", r.p90LatencyMs()), BaselineEvalResult::p90LatencyMs, "%+.1f");

        return sb.toString();
    }

    private void appendAggregateRow(StringBuilder sb,
                                    List<String> labels,
                                    Map<String, BaselineEvalResult> resultByLabel,
                                    String name,
                                    java.util.function.Function<BaselineEvalResult, String> formatter,
                                    java.util.function.ToDoubleFunction<BaselineEvalResult> numericExtractor,
                                    String deltaFormat) {
        sb.append("| ").append(name).append(" |");
        for (String label : labels) {
            sb.append(" ").append(formatter.apply(resultByLabel.get(label))).append(" |");
        }
        if (labels.size() == 2) {
            double delta = numericExtractor.applyAsDouble(resultByLabel.get(labels.get(1)))
                    - numericExtractor.applyAsDouble(resultByLabel.get(labels.get(0)));
            sb.append(" ").append(String.format(deltaFormat, delta)).append(" |");
        }
        sb.append("\n");
    }

    private String buildSplitTable(List<String> labels, ModeComparison modeComparison, String splitName) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append("trainSplit".equals(splitName) ? "Train Split" : "Eval Split (Holdout)").append("\n\n");
        sb.append("| 指标 |");
        for (String label : labels) {
            sb.append(" ").append(label).append(" |");
        }
        if (labels.size() == 2) {
            sb.append(" Δ |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < labels.size(); i++) {
            sb.append("---|");
        }
        if (labels.size() == 2) {
            sb.append("---|");
        }
        sb.append("\n");

        java.util.function.Function<BaselineEvalResult, BaselineEvalResult.SplitBreakdown> selector =
                "trainSplit".equals(splitName) ? BaselineEvalResult::trainSplit : BaselineEvalResult::evalSplit;

        appendSplitRow(sb, labels, modeComparison.resultByLabel(), selector, "通过",
                s -> String.format("%d/%d", s.passedCases(), s.totalCases()),
                s -> (double) s.passedCases() / Math.max(1, s.totalCases()), "%+.3f");
        appendSplitRow(sb, labels, modeComparison.resultByLabel(), selector, "Recall@5",
                s -> String.format("%.3f", s.meanRecallAt5()),
                BaselineEvalResult.SplitBreakdown::meanRecallAt5, "%+.3f");
        appendSplitRow(sb, labels, modeComparison.resultByLabel(), selector, "MRR@5",
                s -> String.format("%.3f", s.meanMrrAt5()),
                BaselineEvalResult.SplitBreakdown::meanMrrAt5, "%+.3f");
        appendSplitRow(sb, labels, modeComparison.resultByLabel(), selector, "nDCG@5",
                s -> String.format("%.3f", s.meanNdcgAt5()),
                BaselineEvalResult.SplitBreakdown::meanNdcgAt5, "%+.3f");
        appendSplitRow(sb, labels, modeComparison.resultByLabel(), selector, "answerKw",
                s -> String.format("%.3f", s.meanAnswerKeywordCoverage()),
                BaselineEvalResult.SplitBreakdown::meanAnswerKeywordCoverage, "%+.3f");

        return sb.toString();
    }

    private void appendSplitRow(StringBuilder sb,
                                List<String> labels,
                                Map<String, BaselineEvalResult> resultByLabel,
                                java.util.function.Function<BaselineEvalResult, BaselineEvalResult.SplitBreakdown> selector,
                                String name,
                                java.util.function.Function<BaselineEvalResult.SplitBreakdown, String> formatter,
                                java.util.function.ToDoubleFunction<BaselineEvalResult.SplitBreakdown> numericExtractor,
                                String deltaFormat) {
        sb.append("| ").append(name).append(" |");
        for (String label : labels) {
            sb.append(" ").append(formatter.apply(selector.apply(resultByLabel.get(label)))).append(" |");
        }
        if (labels.size() == 2) {
            double delta = numericExtractor.applyAsDouble(selector.apply(resultByLabel.get(labels.get(1))))
                    - numericExtractor.applyAsDouble(selector.apply(resultByLabel.get(labels.get(0))));
            sb.append(" ").append(String.format(deltaFormat, delta)).append(" |");
        }
        sb.append("\n");
    }

    private String buildPerCaseTable(List<String> labels, ModeComparison modeComparison) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Per-Case Metrics (nDCG@5)\n\n");
        sb.append("| caseId | type | split |");
        for (String label : labels) {
            sb.append(" ").append(label).append(" |");
        }
        sb.append("\n|---|---|---|");
        for (int i = 0; i < labels.size(); i++) {
            sb.append("---|");
        }
        sb.append("\n");

        for (CaseDiff diff : modeComparison.caseDiffs()) {
            sb.append("| ").append(diff.caseId())
                    .append(" | ").append(diff.type())
                    .append(" | ").append(diff.splitMode()).append(" |");
            for (String label : labels) {
                BaselineEvalResult.CaseResult c = diff.perLabel().get(label);
                sb.append(" ").append(c.rankingCheckApplied() ? String.format("%.3f", c.ndcgAt5()) : "n/a").append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public record ComparisonOutput(
            String generatedAt,
            List<String> labels,
            List<ModeComparison> modes
    ) {
    }

    public record ModeComparison(
            String mode,
            Map<String, BaselineEvalResult> resultByLabel,
            List<CaseDiff> caseDiffs
    ) {
    }

    public record CaseDiff(
            String caseId,
            String type,
            String splitMode,
            Map<String, BaselineEvalResult.CaseResult> perLabel
    ) {
    }
}
