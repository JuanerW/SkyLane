import { api, API_BASE, ApiError, authHeaders, handleUnauthorized, USE_MOCK } from "./api";
import { readAgentEvents } from "./agent-sse";
import type { ActionDecision, AgentEvent, Conversation } from "@/types/agent";

export const agentApi = {
  create: () => api.post<{ conversation_id: string }>("/agent/conversations"),
  history: (id: string, signal?: AbortSignal) => api.get<Conversation>(`/agent/conversations/${id}`, { signal }),
  decide: (id: string, decision: "confirm" | "reject") => api.post<ActionDecision>(`/agent/actions/${id}/${decision}`),
};
export async function sendAgentMessage(id: string, text: string, onEvent: (event: AgentEvent) => void, signal: AbortSignal) {
  if (USE_MOCK) throw new ApiError(503, "REAL_BACKEND_REQUIRED", "Assistant requires the real backend");
  const response = await fetch(`${API_BASE}/agent/conversations/${id}/messages`, {
    method: "POST", headers: { ...authHeaders(), "Content-Type": "application/json", Accept: "text/event-stream, application/json" },
    body: JSON.stringify({ text }), signal,
  });
  if (response.status === 401) handleUnauthorized();
  if (!response.ok) {
    const value = await response.json().catch(() => null);
    throw new ApiError(response.status, value?.error?.code ?? `HTTP_${response.status}`, "Assistant request failed");
  }
  if (!response.body || !response.headers.get("content-type")?.includes("text/event-stream")) throw new ApiError(502, "INVALID_STREAM", "Expected a message stream");
  await readAgentEvents(response.body, onEvent);
}
