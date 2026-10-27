"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useHydrated, useToken } from "@/hooks/useAuth";

/** Redirects to /login?next=… when there is no token. Renders nothing until hydrated. */
export function AuthGuard({ children }: { children: React.ReactNode }) {
  const hydrated = useHydrated();
  const token = useToken();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!hydrated || token) return;
    // Keep the query string so e.g. /ship/plans?… comes back intact (same as lib/api.ts on 401).
    router.replace(`/login?next=${encodeURIComponent(pathname + window.location.search)}`);
  }, [hydrated, token, pathname, router]);

  if (!hydrated || !token) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-slate-400">Loading…</div>;
  }
  return <>{children}</>;
}
