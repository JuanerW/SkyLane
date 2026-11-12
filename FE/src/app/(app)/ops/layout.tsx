"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/hooks/useAuth";
import { LoadingBlock } from "@/components/ui/States";

// Design 8.1: /ops is for operators and admins. The server enforces it (403); this only hides the pages.
const TABS = [
  { href: "/ops", label: "Overview" },
  { href: "/ops/devices", label: "Devices" },
  { href: "/ops/orders", label: "Orders" },
  { href: "/ops/metrics", label: "Metrics" },
];

export default function OpsLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user } = useAuth();

  if (!user) return <LoadingBlock label="Loading" rows={3} />;
  if (user.role !== "operator" && user.role !== "admin") {
    return (
      <div className="mx-auto max-w-md rounded-lg border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
        <div className="mb-1 text-base font-semibold">Operators only</div>
        <p className="mb-4 text-sm text-slate-500">This area is for delivery operators. Your account does not have access.</p>
        <Link href="/ship" className="btn !h-11 !text-sm">
          Back to deliveries
        </Link>
      </div>
    );
  }

  const tabs = user.role === "admin" ? [...TABS, { href: "/ops/admin", label: "Admin" }] : TABS;
  return (
    <div className="mx-auto max-w-[1240px]">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="mb-1 text-2xl font-semibold">Operations</h1>
          <p className="text-sm text-slate-600">
            {user.role === "admin" ? "All operators and devices." : "Your devices and the orders they carry."}
          </p>
        </div>
      </div>
      <nav className="-mx-4 mb-5 flex gap-2 overflow-x-auto px-4 [scrollbar-width:none] sm:mx-0 sm:px-0" aria-label="Operations">
        {tabs.map((t) => {
          const on = t.href === "/ops" ? pathname === "/ops" : pathname.startsWith(t.href);
          return (
            <Link key={t.href} href={t.href} className={`tab flex-none ${on ? "on" : ""}`} aria-current={on ? "page" : undefined}>
              {t.label}
            </Link>
          );
        })}
      </nav>
      {children}
    </div>
  );
}
