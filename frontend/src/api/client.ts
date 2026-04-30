import type { ApiErrorResponse } from "../types/api";

export class ApiError extends Error {
  code: string;
  requestId: string;
  category: string;

  constructor(code: string, message: string, requestId: string, category: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.requestId = requestId;
    this.category = category;
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });

  if (!res.ok) {
    let code = "UNKNOWN";
    let message = `HTTP ${res.status}`;
    let requestId = "";
    let category = "unexpected";

    try {
      const body = (await res.json()) as ApiErrorResponse;
      code = body.code ?? code;
      message = body.message ?? message;
      requestId = body.requestId ?? "";
      category = body.category ?? category;
    } catch {
      // body wasn't JSON
    }

    throw new ApiError(code, message, requestId, category);
  }

  return res.json() as Promise<T>;
}
