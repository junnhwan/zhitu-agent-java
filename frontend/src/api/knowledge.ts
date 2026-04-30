import { request } from "./client";
import type { KnowledgeWriteRequest, KnowledgeWriteResponse } from "../types/api";

export function writeKnowledge(
  question: string,
  answer: string,
  sourceName: string,
): Promise<KnowledgeWriteResponse> {
  const body: KnowledgeWriteRequest = { question, answer, sourceName };
  return request<KnowledgeWriteResponse>("/api/knowledge", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
