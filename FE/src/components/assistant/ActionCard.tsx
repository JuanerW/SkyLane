"use client";
import Link from "next/link";
import { PlanCard } from "@/components/plans/PlanCard";
import { formatTime } from "@/lib/time";
import type { AgentAction } from "@/types/agent";
import type { QuoteOption } from "@/types/contract";

export function ActionCard({ action, busy, disabled, onDecide }: {
  action: AgentAction; busy: boolean; disabled: boolean; onDecide: (id: string, decision: "confirm" | "reject") => void;
}) {
  const summary = action.payload.summary;
  const expired = Date.parse(action.expires_at) <= Date.now();
  const pending = action.status === "pending" && !expired;
  const orderId = action.result?.order_id ?? action.payload.order_id;
  const option: QuoteOption | null = summary?.device_type && summary.price_cents !== undefined && summary.eta_at && summary.departs_at ? {
    option_id: action.payload.option_id ?? action.action_id,
    station: { id: "", name: summary.station_name ?? "Delivery station", lat: 0, lng: 0 },
    device_type: summary.device_type, available: true, unavailable_reason: null, price_cents: summary.price_cents,
    departs_at: summary.departs_at, eta_at: summary.eta_at, distance_m: null, route_points: null, tags: [],
  } : null;
  return (
    <article className={`action-card ${pending ? "action-card-pending" : ""}`} aria-label={`${action.kind === "place_order" ? "Delivery" : "Cancellation"} action`}>
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="font-semibold">{action.kind === "place_order" ? "Confirm delivery" : "Confirm cancellation"}</h3>
        <span className={`action-status action-status-${expired && action.status === "pending" ? "expired" : action.status}`}>{action.status === "pending" && expired ? "expired" : action.status}</span>
      </div>
      {action.kind === "place_order" && option && <PlanCard option={option} quotedAt={Date.now()} selected={false} onSelect={() => {}} readOnly />}
      <dl className="action-details mt-4 space-y-3 text-sm text-slate-600">
        {summary?.pickup_text && <div><dt className="text-xs text-slate-400">From</dt><dd>{summary.pickup_text}</dd></div>}
        {summary?.dropoff_text && <div><dt className="text-xs text-slate-400">To</dt><dd>{summary.dropoff_text}</dd></div>}
        {action.payload.recipient && <div><dt className="text-xs text-slate-400">Recipient</dt><dd>{action.payload.recipient.name} · {action.payload.recipient.phone}</dd></div>}
        {action.payload.order_id && <div><dt className="text-xs text-slate-400">Order</dt><dd className="break-all font-mono">{action.payload.order_id}</dd></div>}
        {action.payload.reason && <div><dt className="text-xs text-slate-400">Reason</dt><dd>{action.payload.reason}</dd></div>}
      </dl>
      {pending && <p className="mt-3 text-xs text-slate-500">Not executed yet. Review the details before confirming. Expires {formatTime(action.expires_at)}.</p>}
      {action.status === "failed" && <p role="status" className="mt-3 text-sm text-amber-800">This action could not be completed. Request a fresh proposal.</p>}
      {pending && <div className="mt-4 flex gap-2">
        <button className="btn flex-1" disabled={busy || disabled} onClick={() => onDecide(action.action_id, "confirm")}>{busy ? "Processing…" : action.kind === "place_order" ? "Confirm delivery" : "Confirm cancellation"}</button>
        <button className="ghost" disabled={busy || disabled} onClick={() => onDecide(action.action_id, "reject")}>Reject</button>
      </div>}
      {action.status === "confirmed" && orderId && <Link className="btn mt-4 w-full" href={`/orders/${orderId}/track`}>View order →</Link>}
    </article>
  );
}
