"use client";

import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { openStream, type StreamState } from "@/lib/sse";
import { orderKey } from "./useOrders";
import { TERMINAL_STATUSES, type OrderStatus, type PositionEvent, type StatusEvent } from "@/types/contract";

/**
 * Subscribes to SSE /orders/{id}/track while the order is not terminal.
 * Status events refresh the order query (timeline, cancellable); position events are kept in state
 * for the map. Pass enabled=false for orders that are already finished.
 */
export function useTrackingStream(orderId: string, enabled: boolean) {
  const qc = useQueryClient();
  const [position, setPosition] = useState<PositionEvent | null>(null);
  const [lastStatus, setLastStatus] = useState<OrderStatus | null>(null);
  const [state, setState] = useState<StreamState>("connecting");

  useEffect(() => {
    if (!enabled) return;
    const close = openStream(`/orders/${orderId}/track`, {
      onState: setState,
      isTerminal: (msg) =>
        msg.event === "status" && TERMINAL_STATUSES.includes((JSON.parse(msg.data) as StatusEvent).status),
      onMessage: (msg) => {
        if (msg.event === "position") {
          setPosition(JSON.parse(msg.data) as PositionEvent);
        } else if (msg.event === "status") {
          const s = JSON.parse(msg.data) as StatusEvent;
          setLastStatus(s.status);
          if (!["dispatched", "picked_up", "in_transit"].includes(s.status)) setPosition(null);
          void qc.invalidateQueries({ queryKey: orderKey(orderId) });
          void qc.invalidateQueries({ queryKey: ["orders"] });
        }
      },
    });
    return close;
  }, [orderId, enabled, qc]);

  return { position, lastStatus, state };
}
