package com.zhituagent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.config.TraceArchiveProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.TokenEstimator;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.rag.KnowledgeSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class TraceArchiveService {

    private static final Logger log = LoggerFactory.getLogger(TraceArchiveService.class);
    private static final int ANSWER_PREVIEW_LIMIT = 240;
    private static final int SNIPPET_PREVIEW_LIMIT = 160;

    private final ObjectMapper objectMapper;
    private final TraceArchiveProperties properties;
    private final TokenEstimator tokenEstimator;
    private final ReentrantLock writeLock = new ReentrantLock();

    public TraceArchiveService(ObjectMapper objectMapper,
                               TraceArchiveProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tokenEstimator = new TokenEstimator();
    }

    public void archiveSuccess(String event,
                               boolean stream,
                               String sessionId,
                               String userId,
                               String requestId,
                               String userMessage,
                               String answer,
                               TraceInfo traceInfo,
                               RouteDecision routeDecision) {
        if (!properties.isEnabled() || traceInfo == null) {
            return;
        }
        append(buildSuccessEntry(
                event,
                stream,
                sessionId,
                userId,
                requestId,
                userMessage,
                answer,
                traceInfo,
                routeDecision
        ));
    }

    public void archiveFailure(String event,
                               boolean stream,
                               String sessionId,
                               String userId,
                               String requestId,
                               String userMessage,
                               String partialAnswer,
                               String errorMessage,
                               long latencyMs,
                               RouteDecision routeDecision,
                               ContextBundle contextBundle) {
        if (!properties.isEnabled()) {
            return;
        }
        append(buildFailureEntry(
                event,
                stream,
                sessionId,
                userId,
                requestId,
                userMessage,
                partialAnswer,
                errorMessage,
                latencyMs,
                routeDecision,
                contextBundle
        ));
    }

    private TraceArchiveEntry buildSuccessEntry(String event,
                                                boolean stream,
                                                String sessionId,
                                                String userId,
                                                String requestId,
                                                String userMessage,
                                                String answer,
                                                TraceInfo traceInfo,
                                                RouteDecision routeDecision) {
        return new TraceArchiveEntry(
                OffsetDateTime.now().toString(),
                safe(event),
                stream,
                safe(sessionId),
                safe(userId),
                safe(requestId),
                safe(userMessage),
                preview(answer, ANSWER_PREVIEW_LIMIT),
                "",
                safe(traceInfo.path()),
                traceInfo.retrievalHit(),
                traceInfo.toolUsed(),
                routeDecision == null ? "" : safe(routeDecision.toolName()),
                safe(traceInfo.retrievalMode()),
                safe(traceInfo.contextStrategy()),
                traceInfo.snippetCount(),
                traceInfo.retrievalCandidateCount(),
                safe(traceInfo.topSource()),
                traceInfo.topScore(),
                safe(traceInfo.rerankModel()),
                traceInfo.rerankTopScore(),
                traceInfo.factCount(),
                traceInfo.inputTokenEstimate(),
                traceInfo.outputTokenEstimate(),
                traceInfo.latencyMs(),
                snippetEntries(routeDecision)
        );
    }

    private TraceArchiveEntry buildFailureEntry(String event,
                                                boolean stream,
                                                String sessionId,
                                                String userId,
                                                String requestId,
                                                String userMessage,
                                                String partialAnswer,
                                                String errorMessage,
                                                long latencyMs,
                                                RouteDecision routeDecision,
                                                ContextBundle contextBundle) {
        List<KnowledgeSnippet> snippets = routeDecision == null || routeDecision.snippets() == null
                ? List.of()
                : routeDecision.snippets();
        KnowledgeSnippet topSnippet = snippets.isEmpty() ? null : snippets.getFirst();
        List<String> modelMessages = contextBundle == null || contextBundle.modelMessages() == null
                ? List.of()
                : contextBundle.modelMessages();
        List<String> facts = contextBundle == null || contextBundle.facts() == null
                ? List.of()
                : contextBundle.facts();

        return new TraceArchiveEntry(
                OffsetDateTime.now().toString(),
                safe(event),
                stream,
                safe(sessionId),
                safe(userId),
                safe(requestId),
                safe(userMessage),
                preview(partialAnswer, ANSWER_PREVIEW_LIMIT),
                safe(errorMessage),
                routeDecision == null ? "direct-answer" : safe(routeDecision.path()),
                routeDecision != null && routeDecision.retrievalHit(),
                routeDecision != null && routeDecision.toolUsed(),
                routeDecision == null ? "" : safe(routeDecision.toolName()),
                routeDecision == null ? "none" : safe(routeDecision.retrievalMode()),
                contextBundle == null ? "recent-summary" : safe(contextBundle.contextStrategy()),
                snippets.size(),
                routeDecision == null ? 0 : Math.max(0, routeDecision.retrievalCandidateCount()),
                topSnippet == null ? "" : safe(topSnippet.source()),
                topSnippet == null ? 0.0 : topSnippet.score(),
                routeDecision == null ? "" : safe(routeDecision.rerankModel()),
                routeDecision == null ? 0.0 : routeDecision.rerankTopScore(),
                facts.size(),
                tokenEstimator.estimateMessages(modelMessages),
                tokenEstimator.estimateText(partialAnswer),
                Math.max(0, latencyMs),
                snippetEntries(routeDecision)
        );
    }

    private List<TraceArchiveEntry.SnippetTraceEntry> snippetEntries(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return List.of();
        }
        return routeDecision.snippets().stream()
                .map(snippet -> new TraceArchiveEntry.SnippetTraceEntry(
                        safe(snippet.source()),
                        safe(snippet.chunkId()),
                        snippet.score(),
                        snippet.denseScore(),
                        snippet.rerankScore(),
                        preview(snippet.content(), SNIPPET_PREVIEW_LIMIT)
                ))
                .toList();
    }

    private void append(TraceArchiveEntry entry) {
        try {
            String line = objectMapper.writeValueAsString(entry);
            Path filePath = resolveFilePath();
            writeLock.lock();
            try {
                Files.createDirectories(filePath.getParent());
                Files.writeString(
                        filePath,
                        line + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } finally {
                writeLock.unlock();
            }
        } catch (JsonProcessingException exception) {
            log.warn("trace 归档序列化失败 trace.archive.serialize_failed message={}", exception.getMessage());
        } catch (IOException exception) {
            log.warn("trace 归档写入失败 trace.archive.write_failed path={} message={}", resolveFilePath(), exception.getMessage());
        }
    }

    private Path resolveFilePath() {
        String fileName = properties.getFilePrefix() + "-" + LocalDate.now() + ".jsonl";
        return Path.of(properties.getDir(), fileName);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String preview(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }
}
