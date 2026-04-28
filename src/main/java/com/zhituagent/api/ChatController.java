package com.zhituagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.api.dto.ChatRequest;
import com.zhituagent.api.dto.ChatResponse;
import com.zhituagent.api.dto.TraceInfo;
import com.zhituagent.config.AppProperties;
import com.zhituagent.context.ContextBundle;
import com.zhituagent.context.ContextManager;
import com.zhituagent.llm.LlmRuntime;
import com.zhituagent.memory.MemoryService;
import com.zhituagent.orchestrator.AgentOrchestrator;
import com.zhituagent.orchestrator.RouteDecision;
import com.zhituagent.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmRuntime llmRuntime;
    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final ContextManager contextManager;
    private final AgentOrchestrator agentOrchestrator;
    private final ChatTraceFactory chatTraceFactory;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public ChatController(LlmRuntime llmRuntime,
                          SessionService sessionService,
                          MemoryService memoryService,
                          ContextManager contextManager,
                          AgentOrchestrator agentOrchestrator,
                          ChatTraceFactory chatTraceFactory,
                          ObjectMapper objectMapper,
                          AppProperties appProperties,
                          ResourceLoader resourceLoader) throws IOException {
        this.llmRuntime = llmRuntime;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.contextManager = contextManager;
        this.agentOrchestrator = agentOrchestrator;
        this.chatTraceFactory = chatTraceFactory;
        this.objectMapper = objectMapper;
        Resource resource = resourceLoader.getResource(appProperties.getSystemPromptLocation());
        this.systemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        long startNanos = System.nanoTime();
        String requestId = requestIdOf(servletRequest);
        sessionService.ensureSession(request.sessionId(), request.userId());
        sessionService.appendMessage(request.sessionId(), request.userId(), "user", request.message());
        RouteDecision routeDecision = agentOrchestrator.decide(request.message());
        logRouteDecision("chat.route.selected", requestId, request.sessionId(), routeDecision);
        ContextBundle contextBundle = contextManager.build(
                systemPrompt,
                memoryService.snapshot(request.sessionId()),
                request.message(),
                buildEvidenceBlock(routeDecision)
        );

        String answer = llmRuntime.generate(
                systemPrompt,
                contextBundle.modelMessages(),
                request.metadata() == null ? Map.of() : request.metadata()
        );

        sessionService.appendMessage(request.sessionId(), request.userId(), "assistant", answer);
        long latencyMs = elapsedMillis(startNanos);
        TraceInfo traceInfo = chatTraceFactory.create(
                routeDecision,
                requestId,
                latencyMs,
                contextBundle.modelMessages(),
                answer
        );
        log.info(
                "对话完成 chat.completed sessionId={} path={} retrievalHit={} toolUsed={} requestId={} answerLength={} latencyMs={}",
                request.sessionId(),
                routeDecision.path(),
                routeDecision.retrievalHit(),
                routeDecision.toolUsed(),
                requestId,
                answer.length(),
                latencyMs
        );
        return new ChatResponse(request.sessionId(), answer, traceInfo);
    }

    @PostMapping(path = "/streamChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        long startNanos = System.nanoTime();
        String requestId = requestIdOf(servletRequest);
        sessionService.ensureSession(request.sessionId(), request.userId());
        sessionService.appendMessage(request.sessionId(), request.userId(), "user", request.message());
        RouteDecision routeDecision = agentOrchestrator.decide(request.message());
        logRouteDecision("chat.stream.route.selected", requestId, request.sessionId(), routeDecision);
        ContextBundle contextBundle = contextManager.build(
                systemPrompt,
                memoryService.snapshot(request.sessionId()),
                request.message(),
                buildEvidenceBlock(routeDecision)
        );

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            StringBuilder answerBuilder = new StringBuilder();
            try {
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data(writeJson(Map.of("sessionId", request.sessionId()))));

                llmRuntime.stream(
                        systemPrompt,
                        contextBundle.modelMessages(),
                        request.metadata() == null ? Map.of() : request.metadata(),
                        token -> {
                            answerBuilder.append(token);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(writeJson(Map.of("content", token))));
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        },
                        () -> {
                            try {
                                sessionService.appendMessage(request.sessionId(), request.userId(), "assistant", answerBuilder.toString());
                                long latencyMs = elapsedMillis(startNanos);
                                TraceInfo traceInfo = chatTraceFactory.create(
                                        routeDecision,
                                        requestId,
                                        latencyMs,
                                        contextBundle.modelMessages(),
                                        answerBuilder.toString()
                                );
                                log.info(
                                        "流式对话完成 chat.stream.completed sessionId={} path={} retrievalHit={} toolUsed={} requestId={} answerLength={} latencyMs={}",
                                        request.sessionId(),
                                        routeDecision.path(),
                                        routeDecision.retrievalHit(),
                                        routeDecision.toolUsed(),
                                        requestId,
                                        answerBuilder.length(),
                                        latencyMs
                                );
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data(writeJson(traceInfo)));
                                emitter.complete();
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }
                );
            } catch (Exception exception) {
                log.error(
                        "流式对话失败 chat.stream.failed sessionId={} requestId={} path={} message={}",
                        request.sessionId(),
                        requestId,
                        routeDecision.path(),
                        exception.getMessage(),
                        exception
                );
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(writeJson(Map.of("code", "INTERNAL_ERROR", "message", exception.getMessage()))));
                } catch (IOException ignored) {
                    // Ignore secondary streaming errors.
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private String buildEvidenceBlock(RouteDecision routeDecision) {
        if (routeDecision == null) {
            return "";
        }
        if (routeDecision.toolUsed() && routeDecision.toolResult() != null) {
            return "TOOL RESULT: " + routeDecision.toolResult().summary();
        }
        if (routeDecision.retrievalHit() && !routeDecision.snippets().isEmpty()) {
            return routeDecision.snippets().stream()
                    .map(snippet -> "[" + snippet.source() + "] " + snippet.content())
                    .reduce((left, right) -> left + "\n---\n" + right)
                    .orElse("");
        }
        return "";
    }

    private void logRouteDecision(String eventName, String requestId, String sessionId, RouteDecision routeDecision) {
        if (routeDecision == null) {
            log.info("路由决策已生成 {} sessionId={} requestId={} path=direct-answer retrievalHit=false toolUsed=false snippetCount=0", eventName, sessionId, requestId);
            return;
        }
        log.info(
                "路由决策已生成 {} sessionId={} requestId={} path={} retrievalHit={} toolUsed={} snippetCount={} topSource={} topScore={}",
                eventName,
                sessionId,
                requestId,
                routeDecision.path(),
                routeDecision.retrievalHit(),
                routeDecision.toolUsed(),
                routeDecision.snippets() == null ? 0 : routeDecision.snippets().size(),
                topSource(routeDecision),
                topScore(routeDecision)
        );
    }

    private String topSource(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return routeDecision.snippets().getFirst().source();
    }

    private String topScore(RouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.snippets() == null || routeDecision.snippets().isEmpty()) {
            return "-";
        }
        return String.format("%.4f", routeDecision.snippets().getFirst().score());
    }

    private String requestIdOf(HttpServletRequest servletRequest) {
        Object requestId = servletRequest.getAttribute("requestId");
        return requestId == null ? "-" : requestId.toString();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
