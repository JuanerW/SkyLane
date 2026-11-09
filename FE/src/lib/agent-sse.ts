import type { AgentEvent } from "../types/agent";

export class AgentStreamError extends Error {
  constructor(public readonly code: string) { super(code); this.name = "AgentStreamError"; }
}

/** Handles split UTF-8, CRLF, comments and multiline data. POST streams are never retried. */
export async function readAgentEvents(body: ReadableStream<Uint8Array>, onEvent: (event: AgentEvent) => void) {
  const reader = body.getReader(); const decoder = new TextDecoder(); let buffer = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) throw new AgentStreamError("STREAM_INTERRUPTED");
      buffer += decoder.decode(value, { stream: true });
      if (buffer.length > 1_000_000) throw new AgentStreamError("INVALID_STREAM");
      let separator: RegExpExecArray | null;
      while ((separator = /\r?\n\r?\n/.exec(buffer))) {
        const block = buffer.slice(0, separator.index);
        buffer = buffer.slice(separator.index + separator[0].length);
        let name = "message"; const data: string[] = [];
        for (const line of block.split(/\r?\n/)) {
          if (!line || line.startsWith(":")) continue;
          const colon = line.indexOf(":");
          const field = colon < 0 ? line : line.slice(0, colon);
          const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
          if (field === "event") name = value;
          if (field === "data") data.push(value);
        }
        if (!data.length) continue;
        let payload: unknown;
        try { payload = JSON.parse(data.join("\n")); } catch { throw new AgentStreamError("INVALID_STREAM"); }
        if (!["delta", "action", "done", "error"].includes(name)) continue;
        const event = { event: name, data: payload } as AgentEvent;
        if (event.event === "delta" && typeof event.data?.text !== "string") throw new AgentStreamError("INVALID_STREAM");
        if (event.event === "action" && (!event.data?.action_id || !event.data?.payload)) throw new AgentStreamError("INVALID_STREAM");
        onEvent(event);
        if (event.event === "error") throw new AgentStreamError(event.data?.error?.code ?? "AGENT_FAILED");
        if (event.event === "done") {
          if (event.data?.success !== true) throw new AgentStreamError("AGENT_FAILED");
          return;
        }
      }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
