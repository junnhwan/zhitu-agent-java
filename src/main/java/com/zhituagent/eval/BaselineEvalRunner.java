package com.zhituagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.api.dto.ChatResponse;
import com.zhituagent.chat.ChatService;
import com.zhituagent.config.EvalProperties;
import com.zhituagent.rag.RetrievalMode;
import com.zhituagent.rag.RetrievalRequestOptions;
import com.zhituagent.session.SessionService;
import com.zhituagent.rag.KnowledgeIngestService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

@Component
class BaselineEvalRunner {

    private final ChatService chatService;
    private final SessionService sessionService;
    private final KnowledgeIngestService knowledgeIngestService;
    private final ObjectMapper objectMapper;
    private final EvalProperties evalProperties;
    private final Set<String> seededKnowledgeKeys = ConcurrentHashMap.newKeySet();

    BaselineEvalRunner(ChatService chatService,
                       SessionService sessionService,
                       KnowledgeIngestService knowledgeIngestService,
                       ObjectMapper objectMapper,
                       EvalProperties evalProperties) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.knowledgeIngestService = knowledgeIngestService;
        this.objectMapper = objectMapper;
        this.evalProperties = evalProperties;
    }

    BaselineEvalResult runBaselineFixture(Path reportPath) throws Exception {
        List<BaselineEvalCase> cases = loadCases(evalProperties.getFixtureResource());
        seedKnowledge(cases);
        BaselineEvalResult report = runFixture(cases, RetrievalMode.DEFAULT, "default");
        writeReport(report, reportPath);
        return report;
    }

    BaselineEvalComparisonReport runModeComparisonFixture(List<String> requestedModes, Path reportPath) throws Exception {
        List<BaselineEvalCase> cases = loadCases(evalProperties.getFixtureResource());
        List<String> resolvedModes = normalizeModes(requestedModes);
        seedKnowledge(cases);

        List<BaselineEvalResult> modeReports = new ArrayList<>();
        for (String modeValue : resolvedModes) {
            modeReports.add(runFixture(cases, RetrievalMode.fromValue(modeValue), modeValue));
        }

        BaselineEvalComparisonReport report = new BaselineEvalComparisonReport(
                evalProperties.getFixtureResource(),
                OffsetDateTime.now().toString(),
                modeReports.size(),
                List.copyOf(resolvedModes),
                List.copyOf(modeReports)
        );
        writeReport(report, reportPath);
        return report;
    }

    private BaselineEvalResult runFixture(List<BaselineEvalCase> cases,
                                          RetrievalMode retrievalMode,
                                          String modeLabel) throws Exception {
        List<BaselineEvalResult.CaseResult> results = new ArrayList<>();
        for (BaselineEvalCase evalCase : cases) {
            results.add(runCase(evalCase, retrievalMode, modeLabel));
        }

        long topSourceCheckCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::topSourceCheckApplied)
                .count();
        long topSourceMatchedCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::topSourceCheckApplied)
                .filter(BaselineEvalResult.CaseResult::topSourceMatched)
                .count();
        long contextStrategyCheckCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::contextStrategyCheckApplied)
                .count();
        long contextStrategyMatchedCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::contextStrategyCheckApplied)
                .filter(BaselineEvalResult.CaseResult::contextStrategyMatched)
                .count();
        long factCheckCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::factCheckApplied)
                .count();
        long factMatchedCount = results.stream()
                .filter(BaselineEvalResult.CaseResult::factCheckApplied)
                .filter(BaselineEvalResult.CaseResult::factCountMatched)
                .count();
        int rankingCheckedCases = (int) results.stream()
                .filter(BaselineEvalResult.CaseResult::rankingCheckApplied)
                .count();
        int keywordCheckedCases = (int) results.stream()
                .filter(BaselineEvalResult.CaseResult::keywordCheckApplied)
                .count();
        double meanHitAt5 = meanOverApplied(results, BaselineEvalResult.CaseResult::rankingCheckApplied,
                result -> result.hitAt5() ? 1.0 : 0.0);
        double meanRecallAt5 = meanOverApplied(results, BaselineEvalResult.CaseResult::rankingCheckApplied,
                BaselineEvalResult.CaseResult::recallAt5);
        double meanMrrAt5 = meanOverApplied(results, BaselineEvalResult.CaseResult::rankingCheckApplied,
                BaselineEvalResult.CaseResult::mrrAt5);
        double meanNdcgAt5 = meanOverApplied(results, BaselineEvalResult.CaseResult::rankingCheckApplied,
                BaselineEvalResult.CaseResult::ndcgAt5);
        double meanAnswerKeywordCoverage = meanOverApplied(results, BaselineEvalResult.CaseResult::keywordCheckApplied,
                BaselineEvalResult.CaseResult::answerKeywordCoverage);
        String resolvedReportMode = resolveReportMode(modeLabel, results);

        return new BaselineEvalResult(
                evalProperties.getFixtureResource(),
                OffsetDateTime.now().toString(),
                resolvedReportMode,
                results.size(),
                (int) results.stream().filter(this::passed).count(),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::routeMatched).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::actualRetrievalHit).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::actualToolUsed).count(), results.size()),
                ratio(results.stream().filter(BaselineEvalResult.CaseResult::summaryMatched).count(), results.size()),
                ratio(topSourceMatchedCount, topSourceCheckCount),
                ratio(contextStrategyMatchedCount, contextStrategyCheckCount),
                ratio(factMatchedCount, factCheckCount),
                average(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList()),
                percentile(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList(), 0.50),
                percentile(results.stream().map(BaselineEvalResult.CaseResult::latencyMs).toList(), 0.90),
                average(results.stream().map(BaselineEvalResult.CaseResult::inputTokenEstimate).toList()),
                average(results.stream().map(BaselineEvalResult.CaseResult::outputTokenEstimate).toList()),
                meanHitAt5,
                meanRecallAt5,
                meanMrrAt5,
                meanNdcgAt5,
                meanAnswerKeywordCoverage,
                rankingCheckedCases,
                keywordCheckedCases,
                List.copyOf(results)
        );
    }

    private BaselineEvalResult.CaseResult runCase(BaselineEvalCase evalCase,
                                                  RetrievalMode retrievalMode,
                                                  String modeLabel) {
        String sessionId = "eval_" + modeLabel.replace('-', '_') + "_" + evalCase.caseId() + "_" + shortId();
        String userId = "eval_user";
        sessionService.ensureSession(sessionId, userId);
        seedHistory(sessionId, userId, evalCase);

        boolean summaryPresentBeforeRun = evalCase.historyTurns().isEmpty()
                ? false
                : sessionSummaryPresent(sessionId);
        ChatResponse response = chatService.chat(
                sessionId,
                userId,
                evalCase.message(),
                "eval-" + shortId(),
                Map.of("evalCaseId", evalCase.caseId()),
                RetrievalRequestOptions.scoped(retrievalMode, allowedSources(evalCase))
        );
        String expectationModeLabel = resolveExpectationModeLabel(modeLabel, response.trace().retrievalMode());
        BaselineEvalCase.ModeExpectation modeExpectation = evalCase.modeExpectationFor(expectationModeLabel);

        String actualPath = response.trace().path();
        boolean actualRetrievalHit = response.trace().retrievalHit();
        boolean actualToolUsed = response.trace().toolUsed();
        String expectedPath = modeExpectation.expectedPath() != null ? modeExpectation.expectedPath() : evalCase.expectedPath();
        boolean expectedRetrievalHit = modeExpectation.expectedRetrievalHit() != null
                ? modeExpectation.expectedRetrievalHit()
                : evalCase.expectedRetrievalHit();
        boolean expectedToolUsed = modeExpectation.expectedToolUsed() != null
                ? modeExpectation.expectedToolUsed()
                : evalCase.expectedToolUsed();
        String expectedTopSource = modeExpectation.expectedTopSource() == null ? "" : modeExpectation.expectedTopSource();
        boolean topSourceCheckApplied = !expectedTopSource.isBlank();
        boolean topSourceMatched = !topSourceCheckApplied || expectedTopSource.equals(response.trace().topSource());
        String expectedContextStrategy = evalCase.expectedContextStrategy() == null ? "" : evalCase.expectedContextStrategy();
        boolean contextStrategyCheckApplied = !expectedContextStrategy.isBlank();
        boolean contextStrategyMatched = !contextStrategyCheckApplied || expectedContextStrategy.equals(response.trace().contextStrategy());
        int expectedFactCountAtLeast = evalCase.expectedFactCountAtLeast() == null ? 0 : Math.max(0, evalCase.expectedFactCountAtLeast());
        int actualFactCount = response.trace().factCount();
        boolean factCheckApplied = evalCase.expectedFactCountAtLeast() != null;
        boolean factCountMatched = !factCheckApplied || actualFactCount >= expectedFactCountAtLeast;

        List<String> retrievedSources = response.trace().retrievedSources();
        List<String> relevantSourceIds = evalCase.relevantSourceIds();
        boolean rankingCheckApplied = !relevantSourceIds.isEmpty();
        Set<String> relevantSet = rankingCheckApplied
                ? Set.copyOf(relevantSourceIds)
                : Set.of();
        boolean hitAt5 = rankingCheckApplied && RankingMetrics.hitAtK(retrievedSources, relevantSet, 5);
        double recallAt5 = rankingCheckApplied
                ? RankingMetrics.recallAtK(retrievedSources, relevantSet, 5)
                : 0.0;
        double mrrAt5 = rankingCheckApplied
                ? RankingMetrics.mrrAtK(retrievedSources, relevantSet, 5)
                : 0.0;
        double ndcgAt5 = rankingCheckApplied
                ? RankingMetrics.ndcgAtK(retrievedSources, relevantSet, 5)
                : 0.0;

        List<String> expectedAnswerKeywords = evalCase.expectedAnswerKeywords();
        boolean keywordCheckApplied = !expectedAnswerKeywords.isEmpty();
        double answerKeywordCoverage = keywordCheckApplied
                ? RankingMetrics.keywordCoverage(response.answer(), expectedAnswerKeywords)
                : 0.0;

        return new BaselineEvalResult.CaseResult(
                evalCase.caseId(),
                evalCase.type(),
                sessionId,
                expectedPath,
                actualPath,
                expectedPath.equals(actualPath),
                expectedRetrievalHit,
                actualRetrievalHit,
                expectedRetrievalHit == actualRetrievalHit,
                expectedToolUsed,
                actualToolUsed,
                expectedToolUsed == actualToolUsed,
                evalCase.expectedSummaryPresentBeforeRun(),
                summaryPresentBeforeRun,
                evalCase.expectedSummaryPresentBeforeRun() == summaryPresentBeforeRun,
                expectedTopSource,
                topSourceCheckApplied,
                topSourceMatched,
                expectedContextStrategy,
                response.trace().retrievalMode(),
                response.trace().contextStrategy(),
                contextStrategyCheckApplied,
                contextStrategyMatched,
                expectedFactCountAtLeast,
                actualFactCount,
                factCheckApplied,
                factCountMatched,
                response.trace().snippetCount(),
                response.trace().retrievalCandidateCount(),
                response.trace().topSource(),
                response.trace().topScore(),
                response.trace().rerankModel(),
                response.trace().rerankTopScore(),
                response.trace().latencyMs(),
                response.trace().inputTokenEstimate(),
                response.trace().outputTokenEstimate(),
                preview(response.answer()),
                relevantSourceIds,
                retrievedSources,
                rankingCheckApplied,
                hitAt5,
                recallAt5,
                mrrAt5,
                ndcgAt5,
                expectedAnswerKeywords,
                keywordCheckApplied,
                answerKeywordCoverage
        );
    }

    private List<BaselineEvalCase> loadCases(String resourcePath) throws IOException {
        String normalizedResourcePath = normalizeResourcePath(resourcePath);
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(normalizedResourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Fixture not found: " + normalizedResourcePath);
            }

            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(this::parseCase)
                    .toList();
        }
    }

    private String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "eval/baseline-chat-cases.jsonl";
        }
        return resourcePath.startsWith("classpath:")
                ? resourcePath.substring("classpath:".length())
                : resourcePath;
    }

    private BaselineEvalCase parseCase(String line) {
        try {
            return objectMapper.readValue(line, BaselineEvalCase.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid eval case: " + line, exception);
        }
    }

    private void seedKnowledge(List<BaselineEvalCase> cases) {
        for (BaselineEvalCase evalCase : cases) {
            for (BaselineEvalCase.KnowledgeSeed knowledgeEntry : evalCase.knowledgeEntries()) {
                String key = knowledgeEntry.question() + "\u0000" + knowledgeEntry.answer() + "\u0000" + knowledgeEntry.sourceName();
                if (seededKnowledgeKeys.add(key)) {
                    knowledgeIngestService.ingest(
                            knowledgeEntry.question(),
                            knowledgeEntry.answer(),
                            knowledgeEntry.sourceName()
                    );
                }
            }
        }
    }

    private void seedHistory(String sessionId, String userId, BaselineEvalCase evalCase) {
        for (BaselineEvalCase.HistoryTurn historyTurn : evalCase.historyTurns()) {
            sessionService.appendMessage(sessionId, userId, "user", historyTurn.user());
            sessionService.appendMessage(sessionId, userId, "assistant", historyTurn.assistant());
        }
    }

    private Set<String> allowedSources(BaselineEvalCase evalCase) {
        return evalCase.knowledgeEntries().stream()
                .map(BaselineEvalCase.KnowledgeSeed::sourceName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String resolveExpectationModeLabel(String modeLabel, String actualRetrievalMode) {
        if (!RetrievalMode.DEFAULT.value().equalsIgnoreCase(modeLabel)) {
            return modeLabel;
        }
        if (actualRetrievalMode == null || actualRetrievalMode.isBlank() || "none".equalsIgnoreCase(actualRetrievalMode)) {
            return modeLabel;
        }
        return actualRetrievalMode;
    }

    private String resolveReportMode(String modeLabel, List<BaselineEvalResult.CaseResult> results) {
        if (!RetrievalMode.DEFAULT.value().equalsIgnoreCase(modeLabel)) {
            return modeLabel;
        }
        return results.stream()
                .map(BaselineEvalResult.CaseResult::retrievalMode)
                .filter(mode -> mode != null && !mode.isBlank() && !"none".equalsIgnoreCase(mode))
                .findFirst()
                .orElse(modeLabel);
    }

    private boolean sessionSummaryPresent(String sessionId) {
        return !sessionService.getSession(sessionId).summary().isBlank();
    }

    private List<String> normalizeModes(List<String> requestedModes) {
        List<String> modes = requestedModes == null || requestedModes.isEmpty()
                ? evalProperties.getModes()
                : requestedModes;
        return modes.stream()
                .map(mode -> RetrievalMode.fromValue(mode).value())
                .distinct()
                .toList();
    }

    private void writeReport(Object report, Path reportPath) throws IOException {
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private boolean passed(BaselineEvalResult.CaseResult result) {
        return result.routeMatched()
                && result.retrievalMatched()
                && result.toolMatched()
                && result.summaryMatched()
                && result.topSourceMatched()
                && result.contextStrategyMatched()
                && result.factCountMatched();
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator / (double) denominator;
    }

    private double meanOverApplied(List<BaselineEvalResult.CaseResult> results,
                                   Predicate<BaselineEvalResult.CaseResult> applied,
                                   ToDoubleFunction<BaselineEvalResult.CaseResult> value) {
        double sum = 0.0;
        long count = 0L;
        for (BaselineEvalResult.CaseResult result : results) {
            if (!applied.test(result)) {
                continue;
            }
            sum += value.applyAsDouble(result);
            count++;
        }
        return count == 0L ? 0.0 : sum / count;
    }

    private double average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        int safeIndex = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(safeIndex);
    }

    private String preview(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        return answer.length() <= 96 ? answer : answer.substring(0, 96) + "...";
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
