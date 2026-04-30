import { useCallback, useRef } from "react";
import { streamChat } from "../api/chat";
import type { TraceInfo } from "../types/api";
import type { AppAction } from "./useSessionManager";

export interface TraceDisplay extends TraceInfo {
  status: "idle" | "streaming" | "complete" | "error";
}

const emptyTrace: Omit<TraceDisplay, "status"> = {
  path: "direct-answer",
  retrievalHit: false,
  toolUsed: false,
  retrievalMode: "dense",
  contextStrategy: "recent-summary",
  requestId: "",
  latencyMs: 0,
  snippetCount: 0,
  topSource: "",
  topScore: 0,
  retrievalCandidateCount: 0,
  rerankModel: "",
  rerankTopScore: 0,
  factCount: 0,
  inputTokenEstimate: 0,
  outputTokenEstimate: 0,
};

export function emptyTraceDisplay(): TraceDisplay {
  return { ...emptyTrace, status: "idle" };
}

export function useStreamingChat(
  dispatch: React.Dispatch<AppAction>,
  onTraceChange: (trace: TraceDisplay) => void,
) {
  const abortRef = useRef<AbortController | null>(null);
  const rafRef = useRef<number>(0);
  const pendingContent = useRef("");
  const pendingSessionId = useRef("");

  const flushUpdate = useCallback(() => {
    if (pendingContent.current && pendingSessionId.current) {
      dispatch({
        type: "UPDATE_STREAMING_MESSAGE",
        payload: {
          sessionId: pendingSessionId.current,
          content: pendingContent.current,
        },
      });
    }
    rafRef.current = 0;
  }, [dispatch]);

  const scheduleFlush = useCallback(() => {
    if (!rafRef.current) {
      rafRef.current = requestAnimationFrame(flushUpdate);
    }
  }, [flushUpdate]);

  const send = useCallback(
    (sessionId: string, userId: string, message: string) => {
      abortRef.current?.abort();
      cancelAnimationFrame(rafRef.current);

      pendingContent.current = "";
      pendingSessionId.current = sessionId;

      dispatch({
        type: "ADD_MESSAGE",
        payload: { sessionId, message: { role: "user", content: message } },
      });

      dispatch({
        type: "ADD_MESSAGE",
        payload: { sessionId, message: { role: "assistant", content: "", isStreaming: true } },
      });

      dispatch({ type: "SET_SENDING", payload: true });
      onTraceChange({ ...emptyTrace, status: "streaming" });

      const controller = streamChat(
        { sessionId, userId, message, metadata: { client: "web" } },
        {
          onStart: () => {},
          onToken: (token) => {
            pendingContent.current += token;
            scheduleFlush();
          },
          onComplete: (trace: TraceInfo) => {
            cancelAnimationFrame(rafRef.current);
            dispatch({
              type: "FINALIZE_STREAMING_MESSAGE",
              payload: { sessionId, content: pendingContent.current },
            });
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({ ...trace, status: "complete" });
          },
          onError: () => {
            cancelAnimationFrame(rafRef.current);
            const finalContent = pendingContent.current || "生成失败，请重试";
            dispatch({
              type: "FINALIZE_STREAMING_MESSAGE",
              payload: { sessionId, content: finalContent },
            });
            dispatch({ type: "SET_SENDING", payload: false });
            onTraceChange({ ...emptyTrace, status: "error" });
          },
        },
      );

      abortRef.current = controller;
    },
    [dispatch, onTraceChange, scheduleFlush],
  );

  const abort = useCallback(() => {
    abortRef.current?.abort();
    cancelAnimationFrame(rafRef.current);
  }, []);

  return { send, abort };
}
