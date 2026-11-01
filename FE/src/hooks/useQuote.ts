"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { CreateOrderResponse, QuoteRequest, QuoteResponse } from "@/types/contract";
import type { ShipmentForm } from "./useShipmentDraft";

export function toQuoteRequest(form: ShipmentForm): QuoteRequest | null {
  if (!form.pickup || !form.dropoff) return null;
  return {
    item: { description: form.description.trim(), fragile: form.fragile },
    weight_kg: Number(form.weight),
    pickup: form.pickup,
    dropoff: form.dropoff,
    notes: form.notes.trim() || undefined,
    arrive_within_minutes: form.arrive_within ?? undefined,
  };
}

/** Quotes are written on the server and expire, so nothing is cached between page visits (gcTime 0). */
export function useQuote(body: QuoteRequest | null) {
  return useQuery({
    queryKey: ["quote", body],
    queryFn: () => api.post<QuoteResponse>("/quotes", body),
    enabled: !!body,
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
  });
}

export interface CreateOrderInput {
  quote_id: string;
  option_id: string;
  recipient: { name: string; phone: string };
  idempotency_key: string;
}

export function useCreateOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ idempotency_key, ...body }: CreateOrderInput) =>
      api.post<CreateOrderResponse>("/orders", body, { headers: { "Idempotency-Key": idempotency_key } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["orders"] }),
  });
}
