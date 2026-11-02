"use client";

import { useCallback, useEffect, useState } from "react";
import type { Place } from "@/types/contract";

// Shared by /ship and /ship/plans. Lives in sessionStorage: survives a refresh, cleared after Place order.
const KEY = "skylane.shipment-draft";

export interface ShipmentForm {
  description: string;
  /** Kept as typed; parsed when the quote is requested. */
  weight: string;
  fragile: boolean;
  pickup: Place | null;
  dropoff: Place | null;
  recipient_name: string;
  recipient_phone: string;
  notes: string;
  /** null = ASAP */
  arrive_within: number | null;
}

export interface ShipmentDraft {
  form: ShipmentForm;
  quote_id?: string;
  option_id?: string;
  idempotency_key?: string;
  /** Set by /ship/plans when the quote rejects the addresses, shown on /ship. */
  address_error?: "OUT_OF_SERVICE_AREA" | "SAME_ADDRESS";
}

export const EMPTY_FORM: ShipmentForm = {
  description: "",
  weight: "",
  fragile: false,
  pickup: null,
  dropoff: null,
  recipient_name: "",
  recipient_phone: "",
  notes: "",
  arrive_within: null,
};

function read(): ShipmentDraft | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as ShipmentDraft) : null;
  } catch {
    return null;
  }
}

function write(draft: ShipmentDraft | null) {
  try {
    if (draft) window.sessionStorage.setItem(KEY, JSON.stringify(draft));
    else window.sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
}

export function useShipmentDraft() {
  const [draft, setDraft] = useState<ShipmentDraft | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setDraft(read());
    setHydrated(true);
  }, []);

  const save = useCallback((next: ShipmentDraft | null) => {
    write(next);
    setDraft(next);
  }, []);

  return { draft, hydrated, save };
}
