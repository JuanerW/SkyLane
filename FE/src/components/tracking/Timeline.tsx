import { formatTime } from "@/lib/time";
import { STATUS_LABEL } from "@/components/orders/StatusBadge";
import { ACTIVE_FLOW, type OrderStatus, type TimelineEntry } from "@/types/contract";

/**
 * Happy-path steps with the time each was reached. Canceled / failed orders show the events that
 * actually happened instead of the full flow.
 */
export function Timeline({ status, entries }: { status: OrderStatus; entries: TimelineEntry[] }) {
  const at = new Map(entries.map((e) => [e.status, e.occurred_at]));
  const steps: OrderStatus[] =
    status === "canceled" || status === "failed" ? entries.map((e) => e.status) : ACTIVE_FLOW;
  const currentIndex = steps.lastIndexOf(status);

  return (
    <ol className="space-y-3">
      {steps.map((s, i) => {
        const reached = at.has(s);
        const current = i === currentIndex;
        return (
          <li key={`${s}-${i}`} className="flex items-start gap-3">
            <span
              className={`mt-1 h-3 w-3 shrink-0 rounded-full border-2 ${
                current
                  ? "border-indigo-600 bg-indigo-600"
                  : reached
                    ? "border-emerald-500 bg-emerald-500"
                    : "border-slate-300 bg-white"
              }`}
            />
            <div className="flex flex-1 justify-between gap-2 text-sm">
              <span className={reached ? "font-medium text-slate-900" : "text-slate-400"}>{STATUS_LABEL[s]}</span>
              <span className="text-slate-500">{reached ? formatTime(at.get(s)!) : ""}</span>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
