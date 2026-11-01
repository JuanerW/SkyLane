"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MIN_QUERY_LENGTH, searchAddress } from "@/lib/photon";

const DEBOUNCE_MS = 300;

/** Debounced Photon search. Stays idle for queries under 3 characters. */
export function useAddressSearch(query: string) {
  const [debounced, setDebounced] = useState(query);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [query]);

  return useQuery({
    queryKey: ["address", debounced],
    queryFn: ({ signal }) => searchAddress(debounced, signal),
    enabled: debounced.length >= MIN_QUERY_LENGTH,
    staleTime: 5 * 60_000,
    retry: false,
  });
}
