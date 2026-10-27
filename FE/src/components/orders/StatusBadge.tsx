import type { OrderStatus } from "@/types/contract";

export const STATUS_LABEL: Record<OrderStatus, string> = {
  confirmed: "Scheduled",
  dispatched: "Dispatched",
  picked_up: "Picked up",
  in_transit: "In transit",
  arrived: "Arrived",
  completed: "Completed",
  canceled: "Canceled",
  failed: "Failed",
};

const STYLE: Record<OrderStatus, string> = {
  confirmed: "bg-sky-100 text-sky-800",
  dispatched: "bg-indigo-100 text-indigo-800",
  picked_up: "bg-indigo-100 text-indigo-800",
  in_transit: "bg-indigo-100 text-indigo-800",
  arrived: "bg-emerald-100 text-emerald-800",
  completed: "bg-emerald-100 text-emerald-800",
  canceled: "bg-slate-200 text-slate-700",
  failed: "bg-rose-100 text-rose-800",
};

export function StatusBadge({ status }: { status: OrderStatus }) {
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${STYLE[status]}`}>
      {STATUS_LABEL[status]}
    </span>
  );
}
