package com.zhituagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineComparisonReporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BaselineComparisonReporter reporter = new BaselineComparisonReporter(objectMapper);

    @Test
    void shouldEmitJsonAndMarkdownComparingV1AndV2(@TempDir Path tempDir) throws Exception {
        Path reportDir = tempDir.resolve("eval-reports");
        Files.createDirectories(reportDir);

        BaselineEvalComparisonReport v1Report = wrapAsComparison(buildResult("hybrid-rerank", 0.50, 0.60, 0.55, 0.10, 12, 16));
        BaselineEvalComparisonReport v2Report = wrapAsComparison(buildResult("hybrid-rerank", 0.85, 0.92, 0.90, 0.30, 15, 16));

        objectMapper.writeValue(reportDir.resolve("baseline-v1-20260501-100000.json").toFile(), v1Report);
        objectMapper.writeValue(reportDir.resolve("baseline-v2-20260501-110000.json").toFile(), v2Report);

        Path jsonOut = reportDir.resolve("baseline-compare-v1-vs-v2.json");
        Path mdOut = reportDir.resolve("baseline-compare-v1-vs-v2.md");
        reporter.compareLatest(reportDir, List.of("v1", "v2"), jsonOut, mdOut);

        assertThat(jsonOut).exists();
        assertThat(mdOut).exists();

        String md = Files.readString(mdOut);
        assertThat(md).contains("v1 vs v2");
        assertThat(md).contains("hybrid-rerank");
        assertThat(md).contains("meanRecallAt5");
        assertThat(md).contains("0.500"); // v1 recall
        assertThat(md).contains("0.850"); // v2 recall
        assertThat(md).contains("+0.350"); // delta
        assertThat(md).contains("Per-Case Metrics");
        assertThat(md).contains("rag-001");

        BaselineComparisonReporter.ComparisonOutput parsed = objectMapper.readValue(
                jsonOut.toFile(), BaselineComparisonReporter.ComparisonOutput.class);
        assertThat(parsed.labels()).containsExactly("v1", "v2");
        assertThat(parsed.modes()).hasSize(1);
        assertThat(parsed.modes().get(0).mode()).isEqualTo("hybrid-rerank");
        assertThat(parsed.modes().get(0).caseDiffs()).hasSize(1);
    }

    @Test
    void shouldPickLatestWhenMultipleReportsSameLabel(@TempDir Path tempDir) throws Exception {
        Path reportDir = tempDir.resolve("eval-reports");
        Files.createDirectories(reportDir);

        BaselineEvalComparisonReport olderV1 = wrapAsComparison(buildResult("hybrid-rerank", 0.10, 0.20, 0.15, 0.05, 5, 16));
        BaselineEvalComparisonReport newerV1 = wrapAsComparison(buildResult("hybrid-rerank", 0.50, 0.60, 0.55, 0.10, 12, 16));
        BaselineEvalComparisonReport v2Report = wrapAsComparison(buildResult("hybrid-rerank", 0.85, 0.92, 0.90, 0.30, 15, 16));

        objectMapper.writeValue(reportDir.resolve("baseline-v1-20260430-100000.json").toFile(), olderV1);
        objectMapper.writeValue(reportDir.resolve("baseline-v1-20260501-100000.json").toFile(), newerV1);
        objectMapper.writeValue(reportDir.resolve("baseline-v2-20260501-110000.json").toFile(), v2Report);

        Path latest = reporter.findLatestReport(reportDir, "v1").orElseThrow();
        assertThat(latest.getFileName().toString()).isEqualTo("baseline-v1-20260501-100000.json");
    }

    private BaselineEvalComparisonReport wrapAsComparison(BaselineEvalResult result) {
        return new BaselineEvalComparisonReport(
                "eval/test.jsonl",
                "2026-05-01T10:00:00Z",
                1,
                List.of(result.mode()),
                List.of(result)
        );
    }

    private BaselineEvalResult buildResult(String mode,
                                           double recall,
                                           double mrr,
                                           double ndcg,
                                           double keywordCoverage,
                                           int passedCases,
                                           int totalCases) {
        BaselineEvalResult.CaseResult caseResult = new BaselineEvalResult.CaseResult(
                "rag-001",
                "rag",
                "session-1",
                "rag",
                "rag",
                true,
                true, true, true,
                false, false, true,
                false, false, true,
                "doc-1", true, true,
                "", mode, "", false, true,
                0, 0, false, true,
                3, 5,
                "doc-1", 0.85,
                "qwen3", 0.91,
                1200L, 100L, 80L,
                "preview answer",
                List.of("doc-1"),
                List.of("doc-1", "doc-2"),
                true, true,
                recall, mrr, ndcg,
                List.of("plan"),
                true, keywordCoverage,
                "train"
        );
        BaselineEvalResult.SplitBreakdown trainSplit = new BaselineEvalResult.SplitBreakdown(
                "train", totalCases, passedCases, 1.0, recall, mrr, ndcg, keywordCoverage, 1, 1
        );
        BaselineEvalResult.SplitBreakdown evalSplit = new BaselineEvalResult.SplitBreakdown(
                "eval", 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0
        );
        return new BaselineEvalResult(
                "eval/test.jsonl",
                "2026-05-01T10:00:00Z",
                mode,
                totalCases, passedCases,
                1.0, 0.5, 0.0, 1.0,
                1.0, 0.0, 0.0,
                1200.0, 1100.0, 1500.0,
                100.0, 80.0,
                1.0, recall, mrr, ndcg, keywordCoverage,
                1, 1,
                trainSplit, evalSplit,
                List.of(caseResult)
        );
    }
}
