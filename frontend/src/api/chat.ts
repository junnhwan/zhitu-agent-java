import type { ChatRequest, ChatResponse, TraceInfo } from "../types/api";
import type { StreamCallbacks } from "../types/events";

export async function sendMessage(req: ChatRequest): Promise<ChatResponse> {
  const res = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });

  if (!res.ok) {
    throw new Error(`Chat request failed: HTTP ${res.status}`);
  }

  return res.json() as Promise<ChatResponse>;
}

export function streamChat(
  req: ChatRequest,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController();

  (async () => {
    const res = await fetch("/api/streamChat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
      signal: controller.signal,
    });

    if (!res.ok) {
      callbacks.onError("HTTP_ERROR", `HTTP ${res.status}`);
      return;
    }

    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let settled = false; // true once we've seen complete or error event

    const ensureSettled = () => {
      if (settled) return;
      settled = true;
      callbacks.onError("STREAM_ABORTED", "流异常断开，未收到结束事件");
    };

    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() ?? "";

        for (const block of blocks) {
          const lines = block.split("\n");
          const eventLine = lines.find((l) => l.startsWith("event:"));
          const dataLine = lines.find((l) => l.startsWith("data:"));
          if (!eventLine || !dataLine) continue;

          const eventName = eventLine.replace("event:", "").trim();
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const payload = JSON.parse(dataLine.replace("data:", "").trim()) as any;

          switch (eventName) {
            case "start":
              callbacks.onStart(payload.sessionId);
              break;
            case "token":
              callbacks.onToken(payload.content);
              break;
            case "complete":
              settled = true;
              callbacks.onComplete({
                path: payload.path ?? "direct-answer",
                retrievalHit: payload.retrievalHit ?? false,
                toolUsed: payload.toolUsed ?? false,
                retrievalMode: payload.retrievalMode ?? "dense",
                contextStrategy: payload.contextStrategy ?? "recent-summary",
                requestId: payload.requestId ?? "",
                latencyMs: payload.latencyMs ?? 0,
                snippetCount: payload.snippetCount ?? 0,
                topSource: payload.topSource ?? "",
                topScore: payload.topScore ?? 0,
                retrievalCandidateCount: payload.retrievalCandidateCount ?? 0,
                rerankModel: payload.rerankModel ?? "",
                rerankTopScore: payload.rerankTopScore ?? 0,
                factCount: payload.factCount ?? 0,
                inputTokenEstimate: payload.inputTokenEstimate ?? 0,
                outputTokenEstimate: payload.outputTokenEstimate ?? 0,
              } satisfies TraceInfo);
              break;
            case "error":
              settled = true;
              callbacks.onError(payload.code, payload.message);
              break;
          }
        }
      }

      // Stream ended (done=true) but we never saw complete/error
      ensureSettled();
    } catch (err: unknown) {
      if (controller.signal.aborted) return;
      ensureSettled();
    }
  })().catch((err: unknown) => {
    if (err instanceof DOMException && err.name === "AbortError") return;
    const message = err instanceof Error ? err.message : String(err);
    callbacks.onError("STREAM_FAILED", message);
  });

  return controller;
}
