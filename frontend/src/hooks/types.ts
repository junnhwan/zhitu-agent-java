export interface SessionState {
  sessionId: string;
  userId: string;
  title: string;
  messages: MessageState[];
  summary: string | null;
  facts: string[];
}

export interface MessageState {
  role: "user" | "assistant";
  content: string;
  timestamp?: string;
  isStreaming?: boolean;
}
