"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { USE_MOCK } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";

const NAV = [
  { href: "/ship", label: "New Delivery" },
  { href: "/orders", label: "My Orders" },
  { href: "/account", label: "Account" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user, logout } = useAuth();

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/90 backdrop-blur">
        {/* Phones: the nav drops to its own row under the logo and scrolls sideways if it has to. */}
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2.5 sm:h-14 sm:flex-nowrap sm:py-0">
          <Link href="/" className="text-lg font-semibold tracking-tight">
            SkyLane
          </Link>
          <nav className="order-last -mx-4 flex w-[calc(100%+2rem)] gap-1 overflow-x-auto whitespace-nowrap px-4 text-sm [scrollbar-width:none] sm:order-none sm:mx-0 sm:w-auto sm:px-0">
            {NAV.map((n) => {
              const active = pathname === n.href || pathname.startsWith(`${n.href}/`);
              return (
                <Link
                  key={n.href}
                  href={n.href}
                  className={`rounded-md px-3 py-1.5 ${active ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"}`}
                >
                  {n.label}
                </Link>
              );
            })}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            {USE_MOCK && (
              <span className="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">Mock data</span>
            )}
            {user && <span className="hidden text-slate-600 sm:inline">{user.display_name}</span>}
            {/* Mock mode has no real token to clear, so signing out would do nothing. */}
            {!USE_MOCK && (
              <button onClick={logout} className="text-slate-500 hover:text-slate-900">
                Sign out
              </button>
            )}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">{children}</main>
    </div>
  );
}
