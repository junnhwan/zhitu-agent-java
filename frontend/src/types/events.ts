import type { TraceInfo } from "./api";

export interface SseStartEvent {
  type: "start";
  sessionId: string;
}

export interface SseTokenEvent {
  type: "token";
  content: string;
}

export type SseCompleteEvent = TraceInfo;

export interface SseErrorEvent {
  type: "error";
  code: string;
  message: string;
}

export type SseEvent = SseStartEvent | SseTokenEvent | SseCompleteEvent | SseErrorEvent;

export interface StreamCallbacks {
  onStart: (sessionId: string) => void;
  onToken: (token: string) => void;
  onComplete: (trace: TraceInfo) => void;
  onError: (code: string, message: string) => void;
}
