"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Station } from "@/types/contract";

/** GET /stations is public (design 8.5), so the home page can call it before login. */
export function useStations() {
  return useQuery({
    queryKey: ["stations"],
    queryFn: () => api.get<Station[]>("/stations", { anonymous: true }),
    staleTime: 10 * 60_000,
  });
}
