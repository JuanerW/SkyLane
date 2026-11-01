"use client";

import Link from "next/link";
import { StatusBadge } from "@/components/orders/StatusBadge";
import { useOrders } from "@/hooks/useOrders";
import { formatDate, formatTime, shortId } from "@/lib/time";
import { TERMINAL_STATUSES, type OrderSummary } from "@/types/contract";

const VEHICLE = { robot: "Ground robot", drone: "Drone" };

function when(o: OrderSummary) {
  if (o.status === "completed" && o.completed_at) return `delivered ${formatDate(o.completed_at)}`;
  if (TERMINAL_STATUSES.includes(o.status)) return formatDate(o.created_at);
  return `arriving ${formatTime(o.eta_at)}`;
}

export function RecentActivity() {
  const orders = useOrders();
  const recent = orders.data?.slice(0, 3) ?? [];

  return (
    <section className="card px-5 pb-2 pt-[18px]">
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-[15px] font-bold">Recent activity</h2>
        <Link href="/orders" className="text-[13px] font-semibold text-[#3730A3]">
          All orders
        </Link>
      </div>
      {orders.isPending && <p className="py-3 text-[13px] text-[#5B6274]">Loading…</p>}
      {orders.isError && <p className="py-3 text-[13px] text-[#5B6274]">Could not load recent orders.</p>}
      {orders.isSuccess && recent.length === 0 && <p className="py-3 text-[13px] text-[#5B6274]">No orders yet.</p>}
      {recent.map((o, i) => (
        <Link
          key={o.order_id}
          href={`/orders/${o.order_id}/track`}
          className={`flex items-center justify-between gap-3 py-3 ${i ? "border-t border-[#EEF0F4]" : ""}`}
        >
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold">
              {shortId(o.order_id)} · {o.recipient_name}
            </div>
            <div className="text-xs text-[#5B6274]">
              {VEHICLE[o.device_type]} · {when(o)}
            </div>
          </div>
          <StatusBadge status={o.status} />
        </Link>
      ))}
    </section>
  );
}
