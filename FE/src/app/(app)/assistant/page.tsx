"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/hooks/useAuth";
import { ApiError, USE_MOCK } from "@/lib/api";
import { agentApi, sendAgentMessage } from "@/lib/agent";
import { AgentStreamError } from "@/lib/agent-sse";
import { ActionCard } from "@/components/assistant/ActionCard";
import { AssistantMessage } from "@/components/assistant/AssistantMessage";
import type { AgentAction, Conversation } from "@/types/agent";
import "./assistant.css";

const ERRORS: Record<string, string> = {
  AGENT_NOT_CONFIGURED: "The assistant is not configured yet. Ask your team to set the backend DeepSeek API key.",
  MODEL_UNAVAILABLE: "The assistant service is unavailable. Your saved conversation is still here.",
  RATE_LIMITED: "Your daily assistant allowance has been reached. Please try again tomorrow.",
  CONVERSATION_BUSY: "A reply is still running. We will refresh its saved history.",
  CONTEXT_TOO_LONG: "This conversation is full. Start a new chat to continue.",
  TOOL_ROUND_LIMIT: "The request needed too many steps. Try a smaller request.",
  MODEL_OUTPUT_LIMIT: "The reply was too long. Try a shorter request.",
  ACTION_NOT_PENDING: "This action was already decided or expired. Its state has been refreshed.",
  QUOTE_EXPIRED: "The delivery quote expired. Ask for a fresh quote.",
  QUOTE_STALE: "Delivery availability changed. Ask for a fresh quote.",
  NOT_CANCELLABLE: "The order can no longer be canceled.",
  DEADLINE_MISSED: "The delivery can no longer meet its deadline. Ask for new options.",
  STREAM_INTERRUPTED: "The connection was interrupted. Saved history has been refreshed; your message was not resent.",
  INVALID_STREAM: "The assistant connection returned an invalid response. Please refresh the chat.",
};
function errorText(error: unknown) {
  const code = error instanceof ApiError || error instanceof AgentStreamError ? error.code : "";
  return ERRORS[code] ?? "The request could not be completed. Refresh the chat before trying again.";
}

export default function AssistantPage() {
  const { user } = useAuth(); const qc = useQueryClient();
  const [id, setId] = useState<string | null>(null);
  const [history, setHistory] = useState<Conversation | null>(null);
  const [actions, setActions] = useState<AgentAction[]>([]);
  const [input, setInput] = useState(""); const [liveText, setLiveText] = useState(""); const [pendingText, setPendingText] = useState("");
  const [sending, setSending] = useState(false); const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null); const [error, setError] = useState("");
  const [refreshRequired, setRefreshRequired] = useState(false);
  const activeId = useRef<string | null>(null);
  const sendingRef = useRef(false); const decidingRef = useRef(false);
  const abort = useRef<AbortController | null>(null); const end = useRef<HTMLDivElement>(null);
  const storageKey = user ? `skylane.assistant.${user.id}` : null;

  const scrollArea = useRef<HTMLDivElement>(null);
  const restoredScroll = useRef(false);
  useEffect(() => {
    if (!storageKey) return;
    try { setInput(sessionStorage.getItem(`${storageKey}.draft`) ?? ""); } catch {}
    restoredScroll.current = false;
  }, [storageKey]);
  const updateInput = (value: string) => {
    setInput(value);
    if (storageKey) try { sessionStorage.setItem(`${storageKey}.draft`, value); } catch {}
  };
  useLayoutEffect(() => {
    const area = scrollArea.current;
    if (!area || loading || !history || restoredScroll.current) return;
    let saved: string | null = null;
    try { saved = sessionStorage.getItem(`${storageKey}.scroll`); } catch {}
    area.scrollTop = saved === null ? area.scrollHeight : Number(saved);
    restoredScroll.current = true;
  }, [history, loading, storageKey]);
  const reload = useCallback(async (conversation: string, signal?: AbortSignal) => {
    try {
      const result = await agentApi.history(conversation, signal);
      if (activeId.current === conversation && !signal?.aborted) { setHistory(result); setActions(result.actions); setRefreshRequired(false); }
    } catch (e) {
      if (activeId.current === conversation && !signal?.aborted) setRefreshRequired(true);
      throw e;
    }
  }, []);
  useEffect(() => {
    if (!storageKey || USE_MOCK) return;
    const controller = new AbortController();
    let stored: string | null = null;
    try { stored = localStorage.getItem(storageKey); } catch {}
    activeId.current = stored; setId(stored); setHistory(null); setActions([]); setError("");
    if (stored) {
      setLoading(true);
      reload(stored, controller.signal).catch((e) => {
        if (controller.signal.aborted || activeId.current !== stored) return;
        if (e instanceof ApiError && e.status === 404) {
          activeId.current = null; setId(null); setRefreshRequired(false); try { localStorage.removeItem(storageKey); } catch {}
        } else setError(errorText(e));
      }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }
    return () => { controller.abort(); abort.current?.abort(); };
  }, [storageKey, reload]);
  const serverBusy = !!history?.messages.some(m => m.content.kind === "turn" && m.content.status === "running" && Date.parse(m.content.lease_until ?? "") > Date.now());
  useEffect(() => {
    if (!id || sending || !serverBusy) return;
    const timer = setInterval(() => { void reload(id).catch(() => {}); }, 2000);
    return () => clearInterval(timer);
  }, [id, sending, serverBusy, reload]);
  useEffect(() => {
    if (!id || sending || busy || !actions.some(a => a.status === "pending")) return;
    const timer = setInterval(() => { void reload(id).catch(() => {}); }, 15000);
    return () => clearInterval(timer);
  }, [id, sending, busy, actions, reload]);
  useLayoutEffect(() => { if ((liveText || pendingText) && scrollArea.current) scrollArea.current.scrollTop = scrollArea.current.scrollHeight; }, [liveText, pendingText]);

  const send = async (event?: React.FormEvent) => {
    event?.preventDefault();
    const text = input.trim();
    if (!text || !storageKey || sendingRef.current || loading || busy || serverBusy || refreshRequired || USE_MOCK) return;
    sendingRef.current = true; setSending(true); setError(""); setLiveText(""); setPendingText(text); updateInput("");
    const controller = new AbortController(); abort.current = controller;
    let conversation = activeId.current;
    let streamStarted = false;
    try {
      if (!conversation) {
        const created = await agentApi.create(); conversation = created.conversation_id;
        activeId.current = conversation; setId(conversation); try { localStorage.setItem(storageKey, conversation); } catch {}
      }
      await sendAgentMessage(conversation, text, (message) => {
        streamStarted = true;
        if (message.event === "delta") setLiveText(value => value + message.data.text);
        if (message.event === "action") {
          const action = message.data;
          setActions(value => [action, ...value.filter(a => a.action_id !== action.action_id)]);
        }
      }, controller.signal);
    } catch (e) {
      if (!streamStarted && !controller.signal.aborted) updateInput(text);
      setError((e as Error).name === "AbortError" ? "Reply stopped. Saved history will be refreshed." : errorText(e));
    }
    finally {
      if (conversation) {
        try { await reload(conversation); setLiveText(""); setPendingText(""); }
        catch { setError("Could not reload saved history. Refresh before sending another message."); }
      }
      if (!conversation) setPendingText("");
      sendingRef.current = false; setSending(false); abort.current = null;
    }
  };
  const decide = async (action: string, decision: "confirm" | "reject") => {
    if (decidingRef.current || sendingRef.current) return;
    decidingRef.current = true; setBusy(action); setError("");
    try {
      const result = await agentApi.decide(action, decision);
      setActions(value => value.map(a => a.action_id === action ? { ...a, ...result } : a));
      void qc.invalidateQueries({ queryKey: ["orders"] }); void qc.invalidateQueries({ queryKey: ["order"] });
    } catch (e) { setError(errorText(e)); }
    finally {
      if (activeId.current) await reload(activeId.current).catch(() => setError("Could not refresh the action. Reload before confirming again."));
      decidingRef.current = false; setBusy(null);
    }
  };
  const newChat = () => {
    if (sendingRef.current || decidingRef.current) return;
    activeId.current = null; setId(null); setHistory(null); setActions([]); setLiveText(""); setPendingText(""); updateInput(""); setError(""); setRefreshRequired(false); setLoading(false);
    if (storageKey) try { localStorage.removeItem(storageKey); sessionStorage.removeItem(`${storageKey}.scroll`); } catch {}
  };
  const messages = history?.messages.flatMap(m => {
    if (m.content.kind !== "message" || m.role === "tool") return [];
    const text = m.content.blocks?.filter(b => b.type === "text").map(b => b.text ?? "").join("") ?? "";
    return text ? [{ id: m.id, role: m.role, text }] : [];
  }) ?? [];
  const locked = !user || sending || loading || !!busy || serverBusy || refreshRequired || USE_MOCK;
  return (
    <div className="assistant mx-auto max-w-6xl">
      <header className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div><p className="mb-1 text-xs font-semibold uppercase tracking-widest text-indigo-600">SkyLane Assistant</p><h1 className="text-2xl font-bold">Plan your next delivery</h1><p className="mt-1 text-sm text-slate-500">Ask, compare, then confirm. You control every order and cancellation.</p></div>
        <button className="ghost" onClick={newChat} disabled={sending || !!busy}>New chat</button>
      </header>
      {USE_MOCK && <p role="status" className="mb-4 rounded-xl bg-amber-50 p-4 text-sm text-amber-900">The assistant requires the real backend. Turn off mock data to use chat.</p>}
      {error && <div role="alert" className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"><span>{error}</span>{id && <button className="ghost" disabled={sending} onClick={() => reload(id).then(() => setError("")).catch(e => setError(errorText(e)))}>Refresh chat</button>}</div>}
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
        <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm" aria-label="Conversation">
          <div ref={scrollArea} onScroll={e => { if (storageKey) try { sessionStorage.setItem(`${storageKey}.scroll`, String(e.currentTarget.scrollTop)); } catch {} }} className="flex h-[52vh] min-h-[320px] flex-col gap-4 overflow-y-auto p-4 sm:p-6" aria-live="polite" aria-busy={sending || loading}>
            {loading && <p role="status" className="text-sm text-slate-500">Loading saved conversation…</p>}
            {!loading && messages.length === 0 && !pendingText && <div className="my-auto text-center">
              <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-50 text-2xl text-indigo-600">✧</div>
              <h2 className="font-semibold">How can I help?</h2><p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-slate-500">Find an address, compare delivery options or check an order. I will ask for any missing details.</p>
              <div className="mt-5 flex flex-wrap justify-center gap-2">{["Help me send a package", "Show my recent orders"].map(s => <button key={s} className="ghost text-sm" disabled={locked} onClick={() => updateInput(s)}>{s}</button>)}</div>
            </div>}
            {messages.map(m => m.role === "user"
              ? <div key={m.id} className="ml-auto max-w-[90%] whitespace-pre-wrap break-words rounded-2xl bg-indigo-600 px-4 py-3 text-sm leading-6 text-white">{m.text}</div>
              : <div key={m.id} className="mr-auto w-full min-w-0 rounded-2xl bg-slate-100 px-4 py-3 text-sm leading-6 text-slate-800"><AssistantMessage text={m.text} /></div>)}
            {pendingText && <div className="ml-auto max-w-[90%] whitespace-pre-wrap break-words rounded-2xl bg-indigo-600 px-4 py-3 text-sm leading-6 text-white">{pendingText}</div>}
            {(sending || liveText) && <div className="mr-auto w-full min-w-0 rounded-2xl bg-slate-100 px-4 py-3 text-sm leading-6 text-slate-800">{liveText ? <AssistantMessage text={liveText} /> : <span className="animate-pulse text-slate-500">Thinking…</span>}</div>}
            {serverBusy && !sending && <p role="status" className="text-sm text-slate-500">A reply is still running. Refreshing saved history…</p>}
            <div ref={end} />
          </div>
          <form onSubmit={send} className="border-t border-slate-200 bg-slate-50 p-4">
            <label htmlFor="assistant-message" className="sr-only">Message the assistant</label>
            <textarea id="assistant-message" className="w-full resize-none rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100" rows={3} maxLength={4000} placeholder="Tell me what you need to deliver…" value={input} disabled={locked} onChange={e => updateInput(e.target.value)} onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(); } }} />
            <div className="mt-2 flex items-center justify-between gap-3"><p className="text-xs text-slate-500">Actions run only after you click Confirm.</p>{sending ? <button type="button" className="ghost" onClick={() => abort.current?.abort()}>Stop reply</button> : <button type="submit" className="btn" disabled={locked || !input.trim()}>Send message</button>}</div>
          </form>
        </section>
        <aside aria-label="Suggested actions" className="space-y-3">
          <div className="flex items-center justify-between"><h2 className="font-semibold">Your action cards</h2><span className="text-xs text-slate-500">{actions.filter(a => a.status === "pending").length} pending</span></div>
          {!actions.length && <p className="rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-5 text-sm leading-6 text-slate-500">Delivery and cancellation proposals appear here for you to review. Chat messages alone cannot execute them.</p>}
          {actions.map(action => <ActionCard key={action.action_id} action={action} busy={busy === action.action_id} disabled={sending || !!busy || serverBusy || refreshRequired} onDecide={decide} />)}
        </aside>
      </div>
    </div>
  );
}
