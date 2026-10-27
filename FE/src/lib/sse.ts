// SSE over fetch + ReadableStream (EventSource cannot send the Authorization header).
// Remembers the last event id and reconnects with Last-Event-ID, backing off 1 / 2 / 4 s (D16).
import { API_BASE, USE_MOCK, authHeaders, handleUnauthorized } from "./api";
import { mockStream } from "./mock";

export interface SseMessage {
  event: string;
  data: string;
  id?: string;
}

export type StreamState = "connecting" | "open" | "reconnecting" | "closed" | "failed";

export interface StreamOptions {
  onMessage: (msg: SseMessage) => void;
  onState?: (state: StreamState) => void;
  /** Return true for a message after which the server closes the stream (terminal status). */
  isTerminal?: (msg: SseMessage) => boolean;
}

const BACKOFF_MS = [1000, 2000, 4000];

/** Opens the stream and returns a function that closes it. */
export function openStream(path: string, opts: StreamOptions): () => void {
  if (USE_MOCK) return mockStream(path, opts);

  const controller = new AbortController();
  let lastEventId: string | undefined;
  let attempt = 0;
  let stopped = false;

  const stop = (state: StreamState) => {
    if (stopped) return;
    stopped = true;
    controller.abort();
    opts.onState?.(state);
  };

  async function run() {
    opts.onState?.("connecting");
    while (!stopped) {
      try {
        const res = await fetch(`${API_BASE}${path}`, {
          headers: {
            Accept: "text/event-stream",
            ...authHeaders(),
            ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}),
          },
          signal: controller.signal,
        });
        if (res.status === 401) {
          handleUnauthorized();
          return stop("failed");
        }
        // 404 and other client errors will not fix themselves by retrying.
        if (res.status >= 400 && res.status < 500) return stop("failed");
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

        attempt = 0;
        opts.onState?.("open");
        if (await read(res.body)) return stop("closed");
      } catch (e) {
        if (stopped || (e as Error).name === "AbortError") return;
      }
      if (stopped) return;
      opts.onState?.("reconnecting");
      await new Promise((r) => setTimeout(r, BACKOFF_MS[Math.min(attempt++, BACKOFF_MS.length - 1)]));
    }
  }

  /** Returns true when a terminal message was seen (no reconnect needed). */
  async function read(body: ReadableStream<Uint8Array>): Promise<boolean> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return false;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, "\n");
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) >= 0) {
        const msg = parseBlock(buffer.slice(0, sep));
        buffer = buffer.slice(sep + 2);
        if (!msg) continue;
        if (msg.id) lastEventId = msg.id;
        opts.onMessage(msg);
        if (opts.isTerminal?.(msg)) return true;
      }
    }
  }

  void run();
  return () => stop("closed");
}

export function parseBlock(block: string): SseMessage | null {
  let event = "message";
  let id: string | undefined;
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue; // comment line = heartbeat
    const i = line.indexOf(":");
    const field = i < 0 ? line : line.slice(0, i);
    const value = i < 0 ? "" : line.slice(i + 1).replace(/^ /, "");
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
    else if (field === "id") id = value;
  }
  return data.length ? { event, data: data.join("\n"), id } : null;
}
