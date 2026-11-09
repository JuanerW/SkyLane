"use client";

import Link from "next/link";
import { useHydrated, useToken } from "@/hooks/useAuth";

// Signed out: "Start a delivery" goes through /login and comes back to /ship (design 8.5).
const START_SIGNED_OUT = `/login?next=${encodeURIComponent("/ship")}`;

/** Where "Start a delivery" leads for the current visitor. */
function useStartHref() {
  const hydrated = useHydrated();
  const token = useToken();
  return { signedIn: hydrated && !!token, href: hydrated && token ? "/ship" : START_SIGNED_OUT };
}

export function StartButton({ className = "btn", label = "Start a delivery" }: { className?: string; label?: string }) {
  const { href } = useStartHref();
  return (
    <Link href={href} className={className}>
      {label}
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M5 12h14M13 6l6 6-6 6" />
      </svg>
    </Link>
  );
}

export function HeaderActions() {
  const { signedIn } = useStartHref();
  if (signedIn) {
    return (
      <div className="flex items-center gap-2">
        <Link href="/orders" className="ghost !hidden sm:!inline-flex">
          My Orders
        </Link>
        <Link href="/ship" className="btn !h-11 !px-4 !text-sm">
          New Delivery
        </Link>
      </div>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <Link href="/login" className="ghost">
        Sign in
      </Link>
      <StartButton className="btn !hidden !h-11 !px-4 !text-sm sm:!inline-flex" />
    </div>
  );
}
