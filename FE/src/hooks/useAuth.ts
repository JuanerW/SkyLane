"use client";

import { useSyncExternalStore } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, USE_MOCK } from "@/lib/api";
import { clearToken, getToken, subscribeToken } from "@/lib/auth";
import type { User } from "@/types/contract";

/** Current token. null on the server and before hydration, so the first render is deterministic. */
export function useToken(): string | null {
  return useSyncExternalStore(
    subscribeToken,
    () => (USE_MOCK ? "mock-token" : getToken()),
    () => null,
  );
}

/** true once we are on the client and can trust useToken(). */
export function useHydrated(): boolean {
  return useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );
}

export function useAuth() {
  const token = useToken();
  const qc = useQueryClient();
  const me = useQuery({
    queryKey: ["me"],
    queryFn: () => api.get<User>("/me"),
    enabled: !!token,
    staleTime: 5 * 60_000,
  });

  const logout = () => {
    clearToken();
    qc.clear();
    window.location.assign("/login");
  };

  return { token, user: me.data ?? null, isLoggedIn: !!token, logout };
}
