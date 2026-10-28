"use client";

import Link from "next/link";
import { ApiError } from "@/lib/api";
import { formatCountdown, formatMiles, formatPrice, formatTime, secondsUntil } from "@/lib/time";
import { useOrder } from "@/hooks/useOrders";
import { useTrackingStream } from "@/hooks/useTrackingStream";
import { useNow } from "@/hooks/useNow";
import { StatusBadge, STATUS_LABEL } from "@/components/orders/StatusBadge";
import { CopyId } from "@/components/orders/CopyId";
import { CancelButton } from "@/components/orders/CancelButton";
import { Timeline } from "@/components/tracking/Timeline";
import { ReviewForm } from "@/components/tracking/ReviewForm";
import { TrackingMap } from "@/components/tracking/TrackingMap";
import { TERMINAL_STATUSES } from "@/types/contract";

// Before departure: Scheduled + countdown + cancel (D17). Moving: map, stage, remaining time.
// Completed: review form. Status changes arrive over SSE and refresh the order query.
export default function TrackPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const order = useOrder(id);
  const o = order.data;
  const live = !!o && !TERMINAL_STATUSES.includes(o.status);
  const { position, state } = useTrackingStream(id, live);
  const now = useNow();

  if (order.isPending) return <p className="text-sm text-slate-500">Loading order…</p>;
  if (order.isError || !o) {
    const notFound = order.error instanceof ApiError && order.error.code === "ORDER_NOT_FOUND";
    return (
      <div className="space-y-2">
        <p className="text-sm text-rose-600">{notFound ? "Order not found." : "Could not load this order."}</p>
        <Link href="/orders" className="text-sm text-indigo-600 underline">
          Back to My Orders
        </Link>
      </div>
    );
  }

  const moving = ["dispatched", "picked_up", "in_transit"].includes(o.status);
  const departIn = now === null ? null : secondsUntil(o.departs_at, now);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Link href="/orders" className="text-sm text-slate-500 hover:text-slate-900">
          ← My Orders
        </Link>
        <h1 className="text-2xl font-semibold">Tracking</h1>
        <CopyId id={o.order_id} />
        <StatusBadge status={o.status} />
        {live && state === "reconnecting" && <span className="text-xs text-amber-600">Reconnecting…</span>}
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <section className="space-y-4">
          {o.status === "confirmed" ? (
            <div className="rounded-lg border border-slate-200 bg-white p-6">
              <p className="text-sm text-slate-500">Scheduled · departs at {formatTime(o.departs_at)}</p>
              <p className="mt-1 text-4xl font-semibold tabular-nums">
                {departIn === null ? "--:--" : formatCountdown(departIn)}
              </p>
              <p className="mt-1 text-sm text-slate-500">until departure</p>
              {/* cancellable is a snapshot; hide it once the countdown hits 0 (server still decides, D11). */}
              {o.cancellable && departIn !== null && departIn > 0 && (
                <div className="mt-4">
                  <CancelButton orderId={o.order_id} />
                </div>
              )}
            </div>
          ) : (
            <TrackingMap
              route={o.route_points}
              deviceType={o.device_type}
              station={o.station}
              pickup={o.pickup}
              dropoff={o.dropoff}
              position={position}
            />
          )}

          {moving && (
            <div className="rounded-lg border border-slate-200 bg-white p-4">
              <div className="flex justify-between text-sm">
                <span className="font-medium">{STATUS_LABEL[o.status]}</span>
                <span className="text-slate-600">
                  {position ? `${formatCountdown(position.remaining_s)} left` : `ETA ${formatTime(o.eta_at)}`}
                </span>
              </div>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100">
                <div
                  className="h-full bg-indigo-500 transition-[width] duration-1000"
                  style={{ width: `${Math.round((position?.progress ?? 0) * 100)}%` }}
                />
              </div>
            </div>
          )}

          {o.status === "completed" && (
            <div className="rounded-lg border border-slate-200 bg-white p-6">
              <h2 className="mb-3 font-medium">Rate this delivery</h2>
              <ReviewForm orderId={o.order_id} review={o.review} />
            </div>
          )}
          {o.status === "canceled" && (
            <div className="rounded-lg border border-slate-200 bg-white p-6 text-sm text-slate-600">
              This order was canceled{o.cancel_reason ? `: ${o.cancel_reason}` : "."}
            </div>
          )}
          {o.status === "failed" && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 p-6 text-sm text-rose-700">
              This delivery failed. Please contact support.
            </div>
          )}
        </section>

        <aside className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <h2 className="mb-3 font-medium">Timeline</h2>
            <Timeline status={o.status} entries={o.timeline} />
          </div>
          <div className="space-y-1 rounded-lg border border-slate-200 bg-white p-4 text-sm">
            <p className="capitalize">
              <span className="text-slate-500">Vehicle:</span> {o.device_type}
              {o.device && ` ${o.device.code}`}
            </p>
            <p>
              <span className="text-slate-500">From:</span> {o.pickup.text}
            </p>
            <p>
              <span className="text-slate-500">To:</span> {o.dropoff.text}
            </p>
            <p>
              <span className="text-slate-500">Distance:</span> {formatMiles(o.distance_m)} ·{" "}
              {formatPrice(o.price_cents)}
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}
