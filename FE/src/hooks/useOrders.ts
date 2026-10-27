"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { OrderDetail, OrderSummary, Review } from "@/types/contract";

// /ship also reads this key (first 3 rows as "recent orders"), so keep limit=100 in both places.
export const ordersKey = ["orders", { limit: 100 }] as const;
export const orderKey = (id: string) => ["order", id] as const;

export function useOrders() {
  return useQuery({
    queryKey: ordersKey,
    queryFn: () => api.get<OrderSummary[]>("/orders?limit=100"),
  });
}

export function useOrder(id: string | null) {
  return useQuery({
    queryKey: orderKey(id ?? ""),
    queryFn: () => api.get<OrderDetail>(`/orders/${id}`),
    enabled: !!id,
  });
}

function useInvalidateOrder() {
  const qc = useQueryClient();
  return (id: string) => {
    void qc.invalidateQueries({ queryKey: orderKey(id) });
    void qc.invalidateQueries({ queryKey: ["orders"] });
  };
}

export function useCancelOrder() {
  const invalidate = useInvalidateOrder();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      api.post<{ status: "canceled" }>(`/orders/${id}/cancel`, reason ? { reason } : {}),
    // On NOT_CANCELLABLE the order already departed; refresh either way.
    onSettled: (_d, _e, vars) => invalidate(vars.id),
  });
}

export function useReview() {
  const invalidate = useInvalidateOrder();
  return useMutation({
    mutationFn: ({ id, rating, comment }: { id: string; rating: number; comment?: string }) =>
      api.post<Review>(`/orders/${id}/review`, { rating, comment: comment || undefined }),
    onSettled: (_d, _e, vars) => invalidate(vars.id),
  });
}
