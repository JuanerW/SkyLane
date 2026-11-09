"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useOrder, useOrders } from "@/hooks/useOrders";
import { formatDateTime, formatPrice, formatTime, shortId } from "@/lib/time";
import { StatusBadge } from "@/components/orders/StatusBadge";
import { CopyId } from "@/components/orders/CopyId";
import { CancelButton } from "@/components/orders/CancelButton";
import { Timeline } from "@/components/tracking/Timeline";
import { Stars } from "@/components/tracking/ReviewForm";
import { EmptyState, ErrorState, LoadingBlock } from "@/components/ui/States";
import { TERMINAL_STATUSES, type OrderSummary } from "@/types/contract";

type Filter = "all" | "active" | "completed" | "canceled" | "failed";

const FILTERS: [Filter, string][] = [
  ["all", "All"],
  ["active", "Active"],
  ["completed", "Completed"],
  ["canceled", "Canceled"],
  ["failed", "Failed"],
];

const VEHICLE = { robot: "Ground robot", drone: "Drone" };

const isActive = (o: OrderSummary) => !TERMINAL_STATUSES.includes(o.status);

function matchesFilter(o: OrderSummary, f: Filter) {
  return f === "all" || (f === "active" ? isActive(o) : o.status === f);
}

/** Order number (the 8 characters shown in the UI, or the full UUID) or recipient name. */
function matchesSearch(o: OrderSummary, q: string) {
  const s = q.trim().replace(/^#/, "").toLowerCase();
  return !s || o.order_id.toLowerCase().startsWith(s) || o.recipient_name.toLowerCase().includes(s);
}

/** "ETA / Done" column. */
function whenText(o: OrderSummary) {
  if (o.status === "completed" && o.completed_at) return `Done ${formatTime(o.completed_at)}`;
  if (o.status === "canceled") return "Canceled";
  if (o.status === "failed") return "Failed";
  return `ETA ${formatTime(o.eta_at)}`;
}

function RowActions({ o }: { o: OrderSummary }) {
  const label = isActive(o) ? "Track live" : o.status === "completed" && !o.reviewed ? "Leave review" : null;
  if (!label) return null;
  return (
    <Link
      href={`/orders/${o.order_id}/track`}
      onClick={(e) => e.stopPropagation()}
      className="text-[13px] font-semibold text-indigo-600 hover:underline"
    >
      {label}
    </Link>
  );
}

export default function OrdersPage() {
  const orders = useOrders();
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const [newestFirst, setNewestFirst] = useState(true);

  const all = useMemo(() => orders.data ?? [], [orders.data]);
  const rows = useMemo(
    () =>
      all
        .filter((o) => matchesFilter(o, filter) && matchesSearch(o, query))
        .sort((a, b) => (newestFirst ? b.created_at.localeCompare(a.created_at) : a.created_at.localeCompare(b.created_at))),
    [all, filter, query, newestFirst],
  );

  const counts = useMemo(
    () => Object.fromEntries(FILTERS.map(([v]) => [v, all.filter((o) => matchesFilter(o, v)).length])) as Record<Filter, number>,
    [all],
  );

  const clear = () => {
    setQuery("");
    setFilter("all");
  };

  return (
    <div className="mx-auto max-w-[1240px]">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="mb-1 text-2xl font-semibold">My orders</h1>
          <p className="text-sm text-slate-600">Active deliveries and your history in San Francisco.</p>
        </div>
        <Link href="/ship" className="btn !h-11 !text-sm">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          New delivery
        </Link>
      </div>

      {orders.isPending && <LoadingBlock label="Loading orders" rows={5} />}
      {orders.isError && <ErrorState message="Could not load your orders." onRetry={() => orders.refetch()} />}
      {orders.isSuccess && all.length === 0 && (
        <EmptyState
          title="No orders yet"
          body="Your deliveries will show up here once you place one."
          action={
            <Link href="/ship" className="btn !h-11 !text-sm">
              Create a delivery
            </Link>
          }
        />
      )}

      {all.length > 0 && (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <div className="relative min-w-0 flex-[1_1_260px] sm:max-w-[380px]">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="2" strokeLinecap="round" className="pointer-events-none absolute left-3 top-[13px]" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="M20 20l-4-4" />
              </svg>
              <input
                type="search"
                className="inp !pl-[38px]"
                aria-label="Search orders"
                placeholder="Search order number or recipient"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </div>
            <div
              className="-mx-4 flex w-[calc(100%+2rem)] gap-2 overflow-x-auto px-4 pb-1 sm:mx-0 sm:w-auto sm:flex-wrap sm:overflow-visible sm:px-0 sm:pb-0 [scrollbar-width:none]"
              role="group"
              aria-label="Status filter"
            >
              {FILTERS.map(([v, name]) => (
                <button
                  key={v}
                  type="button"
                  className={`tab flex-none ${filter === v ? "on" : ""}`}
                  aria-pressed={filter === v}
                  onClick={() => setFilter(v)}
                >
                  {name} <span className="opacity-70">{counts[v]}</span>
                </button>
              ))}
            </div>
            <button type="button" className="ghost sm:ml-auto" onClick={() => setNewestFirst((d) => !d)}>
              Date: {newestFirst ? "Newest first" : "Oldest first"}
            </button>
          </div>

          {rows.length === 0 ? (
            <EmptyState
              title="No orders match"
              body="Try another order number or recipient, or clear the filters."
              action={
                <button type="button" className="ghost" onClick={clear}>
                  Clear filters
                </button>
              }
            />
          ) : (
            <>
              {/* Wide screens: table, as in the UI design. */}
              <section className="card hidden overflow-hidden md:block">
                <table className="w-full text-left text-sm">
                  <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                    <tr>
                      <th className="px-[18px] pb-2.5 pt-3.5">Order</th>
                      <th className="px-3 pb-2.5 pt-3.5">Recipient</th>
                      <th className="hidden px-3 pb-2.5 pt-3.5 lg:table-cell">Date</th>
                      <th className="px-3 pb-2.5 pt-3.5">Method</th>
                      <th className="px-3 pb-2.5 pt-3.5">Status</th>
                      <th className="hidden px-3 pb-2.5 pt-3.5 lg:table-cell">ETA / Done</th>
                      <th className="px-[18px] pb-2.5 pt-3.5 text-right">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((o) => (
                      <tr
                        key={o.order_id}
                        onClick={() => setSelected(o.order_id)}
                        className={`cursor-pointer border-t border-slate-100 hover:bg-slate-50 ${selected === o.order_id ? "bg-slate-50" : ""}`}
                      >
                        <td className="px-[18px] py-3">
                          <CopyId id={o.order_id} />
                        </td>
                        <td className="max-w-[240px] px-3 py-3">
                          <div className="truncate font-semibold">{o.recipient_name}</div>
                          <div className="truncate text-xs text-slate-500">{o.dropoff_text}</div>
                        </td>
                        <td className="hidden px-3 py-3 text-slate-600 lg:table-cell">{formatDateTime(o.created_at)}</td>
                        <td className="px-3 py-3">{VEHICLE[o.device_type]}</td>
                        <td className="px-3 py-3">
                          <div className="flex flex-col items-start gap-1.5">
                            <StatusBadge status={o.status} />
                            <RowActions o={o} />
                          </div>
                        </td>
                        <td className="mono hidden px-3 py-3 text-[13px] lg:table-cell">{whenText(o)}</td>
                        <td className="mono px-[18px] py-3 text-right">{formatPrice(o.price_cents)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>

              {/* Phones: one card per order. */}
              <ul className="flex flex-col gap-3 md:hidden">
                {rows.map((o) => (
                  <li key={o.order_id}>
                    <button type="button" onClick={() => setSelected(o.order_id)} className="card block w-full px-4 py-3.5 text-left">
                      <div className="mb-1.5 flex items-center justify-between gap-2">
                        <span className="mono text-sm text-slate-700">#{shortId(o.order_id)}</span>
                        <StatusBadge status={o.status} />
                      </div>
                      <div className="truncate font-semibold">{o.recipient_name}</div>
                      <div className="truncate text-xs text-slate-500">{o.dropoff_text}</div>
                      <div className="mt-2 flex items-center justify-between text-[13px] text-slate-600">
                        <span>
                          {VEHICLE[o.device_type]} · {whenText(o)}
                        </span>
                        <span className="mono text-slate-900">{formatPrice(o.price_cents)}</span>
                      </div>
                    </button>
                    {/* Outside the card button: a link cannot sit inside a button. */}
                    <div className="px-4 pt-1.5 empty:hidden">
                      <RowActions o={o} />
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}

      {selected && <OrderDrawer id={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function OrderDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: o, isPending, isError, refetch } = useOrder(id);

  return (
    <div className="fixed inset-0 z-30 flex justify-end bg-black/30" onClick={onClose}>
      <aside
        role="dialog"
        aria-label="Order details"
        className="h-full w-full max-w-md overflow-y-auto bg-white px-6 py-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">Order details</h2>
          <button type="button" onClick={onClose} className="ghost !h-9">
            Close
          </button>
        </div>

        {isPending && <LoadingBlock label="Loading order" rows={4} />}
        {isError && <ErrorState message="Could not load this order." onRetry={() => refetch()} />}

        {o && (
          <div className="space-y-5 text-sm">
            <div className="flex items-center justify-between">
              <CopyId id={o.order_id} />
              <StatusBadge status={o.status} />
            </div>

            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 leading-snug">
              <dt className="text-slate-500">Package</dt>
              <dd>
                {o.item.description} · {o.item.weight_kg} kg{o.item.is_fragile && " · Fragile"}
              </dd>
              <dt className="text-slate-500">From</dt>
              <dd>{o.pickup.text}</dd>
              <dt className="text-slate-500">To</dt>
              <dd>{o.dropoff.text}</dd>
              <dt className="text-slate-500">Recipient</dt>
              <dd>
                {o.recipient_name} · {o.recipient_phone}
              </dd>
              <dt className="text-slate-500">Method</dt>
              <dd>
                {VEHICLE[o.device_type]}
                {o.device && ` ${o.device.code}`}
                {o.station && ` · ${o.station.name}`}
              </dd>
              <dt className="text-slate-500">Departs</dt>
              <dd>{formatTime(o.departs_at)}</dd>
              <dt className="text-slate-500">ETA</dt>
              <dd>{formatTime(o.eta_at)}</dd>
              <dt className="text-slate-500">Total</dt>
              <dd className="mono">{formatPrice(o.price_cents)} (estimate)</dd>
              {o.notes && (
                <>
                  <dt className="text-slate-500">Notes</dt>
                  <dd>{o.notes}</dd>
                </>
              )}
              {o.cancel_reason && (
                <>
                  <dt className="text-slate-500">Canceled</dt>
                  <dd>{o.cancel_reason}</dd>
                </>
              )}
            </dl>

            <section>
              <h3 className="mb-2 font-semibold">Timeline</h3>
              <Timeline status={o.status} entries={o.timeline} />
            </section>

            {o.review && (
              <section className="border-t border-slate-100 pt-3">
                <h3 className="mb-1 font-semibold">Your review</h3>
                <Stars value={o.review.rating} />
                {o.review.comment && <p className="mt-1 text-slate-700">{o.review.comment}</p>}
              </section>
            )}

            <div className="flex flex-wrap items-center gap-3 border-t border-slate-100 pt-4">
              <Link href={`/orders/${o.order_id}/track`} className="btn !h-11 !text-sm">
                {TERMINAL_STATUSES.includes(o.status)
                  ? o.status === "completed" && !o.review
                    ? "Leave a review"
                    : "View tracking"
                  : "Track live"}
              </Link>
              {o.cancellable && <CancelButton orderId={o.order_id} />}
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
