"use client";

import { ApiError } from "@/lib/api";
import { useCancelOrder } from "@/hooks/useOrders";

export function CancelButton({ orderId }: { orderId: string }) {
  const cancel = useCancelOrder();
  const code = cancel.error instanceof ApiError ? cancel.error.code : null;

  return (
    <div className="flex items-center gap-3">
      <button
        type="button"
        disabled={cancel.isPending}
        onClick={() => cancel.mutate({ id: orderId })}
        className="rounded-md border border-rose-300 px-3 py-1.5 text-sm text-rose-700 hover:bg-rose-50 disabled:opacity-50"
      >
        {cancel.isPending ? "Canceling…" : "Cancel order"}
      </button>
      {code === "NOT_CANCELLABLE" && <span className="text-sm text-slate-600">Already departed.</span>}
      {cancel.error && code !== "NOT_CANCELLABLE" && (
        <span className="text-sm text-rose-600">Could not cancel. Try again.</span>
      )}
    </div>
  );
}
