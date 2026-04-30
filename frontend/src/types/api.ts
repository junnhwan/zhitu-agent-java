export interface SessionResponse {
  sessionId: string;
  userId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface SessionDetailResponse {
  session: SessionResponse;
  summary: string | null;
  recentMessages: ChatMessageView[];
  facts: string[];
}

export interface ChatMessageView {
  role: "user" | "assistant";
  content: string;
  timestamp: string;
}

export interface ChatRequest {
  sessionId: string;
  userId: string;
  message: string;
  metadata?: Record<string, unknown>;
}

export interface ChatResponse {
  sessionId: string;
  answer: string;
  trace: TraceInfo;
}

export interface TraceInfo {
  path: string;
  retrievalHit: boolean;
  toolUsed: boolean;
  retrievalMode: string;
  contextStrategy: string;
  requestId: string;
  latencyMs: number;
  snippetCount: number;
  topSource: string;
  topScore: number;
  retrievalCandidateCount: number;
  rerankModel: string;
  rerankTopScore: number;
  factCount: number;
  inputTokenEstimate: number;
  outputTokenEstimate: number;
}

export interface SessionCreateRequest {
  userId: string;
  title?: string;
}

export interface KnowledgeWriteRequest {
  question: string;
  answer: string;
  sourceName: string;
}

export interface KnowledgeWriteResponse {
  success: boolean;
  sourceName: string;
  message: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  requestId: string;
  category: string;
}
