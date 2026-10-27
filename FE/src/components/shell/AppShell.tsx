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
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4">
          <Link href="/ship" className="text-lg font-semibold tracking-tight">
            SkyLane
          </Link>
          <nav className="flex gap-1 text-sm">
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
            {user && <span className="text-slate-600">{user.display_name}</span>}
            <button onClick={logout} className="text-slate-500 hover:text-slate-900">
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">{children}</main>
    </div>
  );
}
