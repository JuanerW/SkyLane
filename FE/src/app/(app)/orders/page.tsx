"use client";

import Link from "next/link";
import { useState } from "react";
import { useOrder, useOrders } from "@/hooks/useOrders";
import { formatDateTime, formatPrice, formatTime } from "@/lib/time";
import { StatusBadge } from "@/components/orders/StatusBadge";
import { CopyId } from "@/components/orders/CopyId";
import { CancelButton } from "@/components/orders/CancelButton";
import { Timeline } from "@/components/tracking/Timeline";
import { Stars } from "@/components/tracking/ReviewForm";
import { TERMINAL_STATUSES } from "@/types/contract";

// Round 1: list + detail drawer. Search, status tabs and sorting are round 2.
export default function OrdersPage() {
  const orders = useOrders();
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">My Orders</h1>

      {orders.isPending && <p className="text-sm text-slate-500">Loading orders…</p>}
      {orders.isError && (
        <p className="text-sm text-rose-600">
          Could not load orders.{" "}
          <button className="underline" onClick={() => orders.refetch()}>
            Retry
          </button>
        </p>
      )}
      {orders.data?.length === 0 && (
        <div className="rounded-lg border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500">
          No orders yet.{" "}
          <Link href="/ship" className="text-indigo-600 underline">
            Create a delivery
          </Link>
        </div>
      )}

      {!!orders.data?.length && (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-2">Order</th>
                <th className="px-4 py-2">Status</th>
                <th className="hidden px-4 py-2 md:table-cell">Route</th>
                <th className="px-4 py-2">Recipient</th>
                <th className="hidden px-4 py-2 sm:table-cell">Created</th>
                <th className="px-4 py-2 text-right">Price</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.map((o) => (
                <tr
                  key={o.order_id}
                  onClick={() => setSelected(o.order_id)}
                  className="cursor-pointer border-t border-slate-100 hover:bg-slate-50"
                >
                  <td className="px-4 py-3">
                    <CopyId id={o.order_id} />
                    <div className="text-xs capitalize text-slate-500">{o.device_type}</div>
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={o.status} />
                  </td>
                  <td className="hidden max-w-xs px-4 py-3 md:table-cell">
                    <div className="truncate">{o.pickup_text}</div>
                    <div className="truncate text-slate-500">→ {o.dropoff_text}</div>
                  </td>
                  <td className="px-4 py-3">{o.recipient_name}</td>
                  <td className="hidden px-4 py-3 text-slate-600 sm:table-cell">{formatDateTime(o.created_at)}</td>
                  <td className="px-4 py-3 text-right">{formatPrice(o.price_cents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && <OrderDrawer id={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function OrderDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: o, isPending, isError } = useOrder(id);

  return (
    <div className="fixed inset-0 z-30 flex justify-end bg-black/30" onClick={onClose}>
      <aside
        role="dialog"
        aria-label="Order details"
        className="h-full w-full max-w-md overflow-y-auto bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">Order details</h2>
          <button onClick={onClose} aria-label="Close" className="text-slate-500 hover:text-slate-900">
            ✕
          </button>
        </div>

        {isPending && <p className="text-sm text-slate-500">Loading…</p>}
        {isError && <p className="text-sm text-rose-600">Could not load this order.</p>}

        {o && (
          <div className="space-y-5 text-sm">
            <div className="flex items-center justify-between">
              <CopyId id={o.order_id} />
              <StatusBadge status={o.status} />
            </div>

            <dl className="grid grid-cols-3 gap-x-3 gap-y-2">
              <dt className="text-slate-500">Item</dt>
              <dd className="col-span-2">
                {o.item.description} · {o.item.weight_kg} kg{o.item.is_fragile && " · Fragile"}
              </dd>
              <dt className="text-slate-500">From</dt>
              <dd className="col-span-2">{o.pickup.text}</dd>
              <dt className="text-slate-500">To</dt>
              <dd className="col-span-2">{o.dropoff.text}</dd>
              <dt className="text-slate-500">Recipient</dt>
              <dd className="col-span-2">
                {o.recipient_name} · {o.recipient_phone}
              </dd>
              <dt className="text-slate-500">Vehicle</dt>
              <dd className="col-span-2 capitalize">
                {o.device_type}
                {o.device && ` ${o.device.code}`}
                {o.station && ` · ${o.station.name}`}
              </dd>
              <dt className="text-slate-500">Departs</dt>
              <dd className="col-span-2">{formatTime(o.departs_at)}</dd>
              <dt className="text-slate-500">ETA</dt>
              <dd className="col-span-2">{formatTime(o.eta_at)}</dd>
              <dt className="text-slate-500">Price</dt>
              <dd className="col-span-2">{formatPrice(o.price_cents)} (estimate)</dd>
              {o.notes && (
                <>
                  <dt className="text-slate-500">Notes</dt>
                  <dd className="col-span-2">{o.notes}</dd>
                </>
              )}
              {o.cancel_reason && (
                <>
                  <dt className="text-slate-500">Canceled</dt>
                  <dd className="col-span-2">{o.cancel_reason}</dd>
                </>
              )}
            </dl>

            <section>
              <h3 className="mb-2 font-medium">Timeline</h3>
              <Timeline status={o.status} entries={o.timeline} />
            </section>

            {o.review && (
              <section>
                <h3 className="mb-1 font-medium">Your review</h3>
                <Stars value={o.review.rating} />
                {o.review.comment && <p className="mt-1 text-slate-700">{o.review.comment}</p>}
              </section>
            )}

            <div className="flex flex-wrap items-center gap-3 border-t border-slate-100 pt-4">
              <Link
                href={`/orders/${o.order_id}/track`}
                className="rounded-md bg-slate-900 px-4 py-2 text-white hover:bg-slate-700"
              >
                {TERMINAL_STATUSES.includes(o.status) ? (o.status === "completed" && !o.review ? "Leave a review" : "View tracking") : "Track"}
              </Link>
              {o.cancellable && <CancelButton orderId={o.order_id} />}
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
