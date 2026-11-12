"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime, formatPrice, formatTime } from "@/lib/time";
import { StatusBadge } from "@/components/orders/StatusBadge";
import { EmptyState, ErrorState, LoadingBlock } from "@/components/ui/States";
import type { OpsOrder } from "@/types/ops";

const STATUSES: [string, string][] = [
  ["", "All"],
  ["confirmed", "Scheduled"],
  ["active", "In progress"],
  ["completed", "Completed"],
  ["canceled", "Canceled"],
  ["failed", "Failed"],
];

/** "YYYY-MM-DD" (local) -> ISO start of that day. */
const dayStart = (d: string) => (d ? new Date(`${d}T00:00`).toISOString() : "");

export default function OpsOrdersPage() {
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const params = new URLSearchParams();
  if (status) params.set("status", status);
  if (from) params.set("from", dayStart(from));
  if (to) params.set("to", new Date(new Date(`${to}T00:00`).getTime() + 86_400_000).toISOString());
  const qs = params.toString();

  const orders = useQuery({
    queryKey: ["ops", "orders", qs],
    queryFn: () => api.get<OpsOrder[]>(`/ops/orders${qs ? `?${qs}` : ""}`),
    refetchInterval: 15_000,
  });

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div className="flex flex-wrap gap-2" role="group" aria-label="Status filter">
          {STATUSES.map(([v, label]) => (
            <button key={v} type="button" className={`tab ${status === v ? "on" : ""}`} aria-pressed={status === v} onClick={() => setStatus(v)}>
              {label}
            </button>
          ))}
        </div>
        <label className="flex flex-col gap-1 text-sm sm:ml-auto">
          <span className="lbl">From</span>
          <input type="date" className="inp !h-10 !w-auto" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="lbl">To</span>
          <input type="date" className="inp !h-10 !w-auto" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </div>

      {orders.isPending && <LoadingBlock label="Loading orders" rows={5} />}
      {orders.isError && <ErrorState message="Could not load orders." onRetry={() => orders.refetch()} />}
      {orders.data?.length === 0 && <EmptyState title="No orders" body="No orders match these filters." />}
      {!!orders.data?.length && (
        <section className="card overflow-x-auto">
          <table className="w-full min-w-[760px] text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-2.5">Order</th>
                <th className="px-3 py-2.5">Device</th>
                <th className="px-3 py-2.5">Recipient</th>
                <th className="px-3 py-2.5">Status</th>
                <th className="px-3 py-2.5">Departs</th>
                <th className="px-3 py-2.5">ETA / Done</th>
                <th className="px-4 py-2.5 text-right">Total</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.map((o) => (
                <tr key={o.order_id} className={`border-t border-slate-100 ${o.late ? "bg-rose-50" : ""}`}>
                  <td className="mono px-4 py-2.5">#{o.order_id.slice(0, 8)}</td>
                  <td className="px-3 py-2.5">
                    <div className="mono">{o.device_code}</div>
                    <div className="text-xs text-slate-500">{o.station_name}</div>
                  </td>
                  <td className="max-w-[220px] px-3 py-2.5">
                    <div className="truncate font-semibold">{o.recipient_name}</div>
                    {/* D29: operators only see the last 4 digits. */}
                    <div className="truncate text-xs text-slate-500">
                      ···{o.recipient_phone_last4} · {o.dropoff_text}
                    </div>
                  </td>
                  <td className="px-3 py-2.5">
                    <div className="flex flex-col items-start gap-1">
                      <StatusBadge status={o.status} />
                      {o.late && <span className="text-xs font-semibold text-rose-700">Late</span>}
                    </div>
                  </td>
                  <td className="mono px-3 py-2.5 text-[13px]">{formatDateTime(o.departs_at)}</td>
                  <td className="mono px-3 py-2.5 text-[13px]">
                    {o.completed_at ? `Done ${formatTime(o.completed_at)}` : `ETA ${formatTime(o.eta_at)}`}
                  </td>
                  <td className="mono px-4 py-2.5 text-right">{formatPrice(o.price_cents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
