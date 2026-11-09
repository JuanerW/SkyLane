import test from "node:test";
import assert from "node:assert/strict";
import { readAgentEvents, AgentStreamError } from "../src/lib/agent-sse";
import type { AgentEvent } from "../src/types/agent";

const encoder = new TextEncoder();
function stream(text: string, bytewise = false) {
  const bytes = encoder.encode(text);
  return new ReadableStream<Uint8Array>({ start(c) {
    if (bytewise) for (const byte of bytes) c.enqueue(new Uint8Array([byte])); else c.enqueue(bytes);
    c.close();
  } });
}
const done = 'event: done\ndata: {"turn_id":"t","success":true}\n\n';
test("split UTF-8 and CRLF with multiline data", async () => {
  const values: AgentEvent[] = [];
  await readAgentEvents(stream(': ping\r\n\r\nevent: delta\r\ndata: {"text":\r\ndata: "你好"}\r\n\r\n' + done, true), e => values.push(e));
  assert.deepEqual(values[0], { event: "delta", data: { text: "你好" } });
  assert.equal(values[1].event, "done");
});
test("forwards an action card and then done", async () => {
  const events: AgentEvent[] = [];
  const action = { action_id: "a", kind: "cancel_order", payload: { order_id: "o" }, status: "pending", expires_at: "2099-01-01T00:00:00Z", result: null };
  await readAgentEvents(stream('event: action\ndata: ' + JSON.stringify(action) + '\n\n' + done), e => events.push(e));
  assert.deepEqual(events[0], { event: "action", data: action });
});
test("server errors preserve the code and do not reconnect", async () => {
  await assert.rejects(readAgentEvents(stream('event: error\ndata: {"error":{"code":"RATE_LIMITED"}}\n\n'), () => {}), { code: "RATE_LIMITED" });
});
test("premature EOF does not imply success", async () => {
  await assert.rejects(readAgentEvents(stream('event: delta\ndata: {"text":"partial"}\n\n'), () => {}), { code: "STREAM_INTERRUPTED" });
});
test("unsuccessful done is an error", async () => {
  await assert.rejects(readAgentEvents(stream('event: done\ndata: {"success":false}\n\n'), () => {}), { code: "AGENT_FAILED" });
});
test("invalid JSON is rejected", async () => {
  await assert.rejects(readAgentEvents(stream('event: delta\ndata: broken\n\n'), () => {}), AgentStreamError);
});
test("terminal event cancels the reader", async () => {
  let canceled = false;
  const body = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(encoder.encode(done)); }, cancel() { canceled = true; } });
  await readAgentEvents(body, () => {});
  assert.equal(canceled, true);
});
