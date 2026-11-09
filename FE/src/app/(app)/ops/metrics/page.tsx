"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { ErrorState, LoadingBlock } from "@/components/ui/States";
import type { OpsMetrics } from "@/types/ops";

const RANGES: [number, string][] = [
  [1, "Today"],
  [7, "7 days"],
  [30, "30 days"],
];

const pct = (v: number | null) => (v === null ? "–" : `${Math.round(v * 100)}%`);

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="card px-4 py-4">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mono mt-1 text-3xl font-medium">{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

export default function OpsMetricsPage() {
  const [days, setDays] = useState(7);
  const metrics = useQuery({
    queryKey: ["ops", "metrics", days],
    queryFn: () => {
      const from = days === 1 ? new Date(new Date().setHours(0, 0, 0, 0)) : new Date(Date.now() - days * 86_400_000);
      return api.get<OpsMetrics>(`/ops/metrics?from=${encodeURIComponent(from.toISOString())}`);
    },
  });
  const m = metrics.data;
  const ratingTotal = m ? Object.values(m.ratings).reduce((a, b) => a + b, 0) : 0;

  return (
    <div>
      <div className="mb-4 flex gap-2" role="group" aria-label="Time range">
        {RANGES.map(([d, label]) => (
          <button key={d} type="button" className={`tab ${days === d ? "on" : ""}`} aria-pressed={days === d} onClick={() => setDays(d)}>
            {label}
          </button>
        ))}
      </div>
      {metrics.isPending && <LoadingBlock label="Loading metrics" rows={2} />}
      {metrics.isError && <ErrorState message="Could not load metrics." onRetry={() => metrics.refetch()} />}
      {m && (
        <div className="flex flex-col gap-5">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Tile label="Completed" value={String(m.completed)} />
            <Tile label="On time" value={pct(m.on_time_rate)} hint="Done by the promised ETA" />
            <Tile
              label="Avg delivery"
              value={m.avg_delivery_minutes === null ? "–" : `${Math.round(m.avg_delivery_minutes)} min`}
              hint="Departure to drop-off"
            />
            <Tile label="Utilization" value={pct(m.utilization)} hint="Busy time across devices" />
          </div>
          <section className="card px-5 py-4">
            <h2 className="mb-3 text-base font-semibold">Ratings</h2>
            {ratingTotal === 0 ? (
              <p className="text-sm text-slate-500">No reviews in this period.</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {[5, 4, 3, 2, 1].map((r) => {
                  const n = m.ratings[String(r)] ?? 0;
                  return (
                    <li key={r} className="flex items-center gap-3 text-sm">
                      <span className="w-10 text-slate-600">{r} ★</span>
                      <span className="h-2.5 flex-1 overflow-hidden rounded-full bg-slate-100">
                        <span className="block h-full rounded-full bg-indigo-500" style={{ width: `${(n / ratingTotal) * 100}%` }} />
                      </span>
                      <span className="mono w-8 text-right">{n}</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
