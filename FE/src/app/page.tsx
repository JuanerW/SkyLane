import Link from "next/link";
import { CoverageMap } from "@/components/home/CoverageMap";
import { HeaderActions, StartButton } from "@/components/home/AuthActions";

// Public home page (design 8.5). Signed-in visitors see it too; the header then offers New Delivery.

const STEPS = [
  {
    title: "Describe the package",
    body: "What it is, how much it weighs, and the pickup and delivery addresses. Address suggestions keep you inside the service area.",
  },
  {
    title: "Compare six options",
    body: "Every station quotes a ground robot and a drone. See arrival time, estimated price and distance side by side, then place the order.",
  },
  {
    title: "Track it live",
    body: "Follow the vehicle on the map from the station to pickup to your recipient, with each step on a timeline. Rate it when it lands.",
  },
];

const VEHICLES = [
  {
    name: "Ground robot",
    color: "#4F46E5",
    icon: (
      <>
        <rect x="4" y="9" width="16" height="9" rx="2" />
        <circle cx="8" cy="20" r="1.5" />
        <circle cx="16" cy="20" r="1.5" />
        <path d="M12 9V5M10 5h4" />
      </>
    ),
    facts: ["Up to 10 kg", "Follows the street network", "Avoids the steepest hills, like Twin Peaks and Nob Hill"],
  },
  {
    name: "Drone",
    color: "#C2410C",
    icon: (
      <>
        <circle cx="12" cy="12" r="2.5" />
        <path d="M9.5 9.5L5 5M14.5 9.5L19 5M9.5 14.5L5 19M14.5 14.5L19 19" />
        <circle cx="4" cy="4" r="2" />
        <circle cx="20" cy="4" r="2" />
        <circle cx="4" cy="20" r="2" />
        <circle cx="20" cy="20" r="2" />
      </>
    ),
    facts: ["Up to 2 kg", "Flies in a straight line, over traffic", "Round trip of up to 15 km from its station"],
  },
];

/** Static illustration of the plan cards, so first-time visitors see what they will compare. */
function ExamplePlans() {
  const rows = [
    { name: "Drone", station: "SoMa Station", min: "11 min", price: "$4.20", tag: "Fastest", tagCls: "bg-orange-100 text-orange-800" },
    { name: "Ground Robot", station: "Mission Station", min: "34 min", price: "$2.90", tag: "Lowest Cost", tagCls: "bg-emerald-100 text-emerald-800" },
  ];
  return (
    <div className="card relative p-4 sm:p-5" aria-label="Example delivery options">
      <div className="mb-3 flex items-center justify-between text-xs text-slate-500">
        <span className="font-semibold uppercase tracking-wide">Example options</span>
        <span>Ferry Building → Dolores Park</span>
      </div>
      <div className="flex flex-col gap-3">
        {rows.map((r, i) => (
          <div key={r.name} className={`rounded-lg border bg-white p-4 ${i === 0 ? "border-slate-900 ring-1 ring-slate-900" : "border-slate-200"}`}>
            <div className="mb-2 flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{r.name}</div>
                <div className="text-xs text-slate-500">{r.station}</div>
              </div>
              <span className={`badge ${r.tagCls}`}>{r.tag}</span>
            </div>
            <div className="flex items-baseline gap-6">
              <div className="mono text-2xl font-medium">{r.min}</div>
              <div className="mono text-2xl font-medium">{r.price}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function HomePage() {
  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-4 px-4">
          <Link href="/" className="text-lg font-semibold tracking-tight">
            SkyLane
          </Link>
          <nav className="hidden items-center gap-1 text-sm font-medium text-slate-600 md:flex" aria-label="Sections">
            <a href="#how" className="rounded-md px-3 py-2 hover:bg-slate-100">
              How it works
            </a>
            <a href="#vehicles" className="rounded-md px-3 py-2 hover:bg-slate-100">
              Vehicles
            </a>
            <a href="#coverage" className="rounded-md px-3 py-2 hover:bg-slate-100">
              Coverage
            </a>
          </nav>
          <HeaderActions />
        </div>
      </header>

      <main>
        <section className="bg-white">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-10 px-4 py-12 sm:px-8 sm:py-20">
            <div className="min-w-0 flex-[1_1_440px]">
              <div className="mb-3 inline-flex items-center gap-2 rounded-full bg-sky-100 px-3 py-1 text-[13px] font-medium text-sky-800">
                Same-day delivery in San Francisco
              </div>
              <h1 className="mb-4 text-4xl font-semibold leading-tight tracking-tight sm:text-5xl">
                Send it across the city by robot or drone.
              </h1>
              <p className="mb-7 max-w-[520px] text-base leading-relaxed text-slate-600 sm:text-lg">
                Tell us what you are sending and where. SkyLane compares ground robots and drones from three stations, so
                you can pick the fastest or the cheapest and watch it arrive live.
              </p>
              <div className="flex flex-wrap gap-3">
                <StartButton />
                <a href="#coverage" className="ghost !h-12">
                  See where we deliver
                </a>
              </div>
            </div>
            <div className="min-w-0 flex-[1_1_360px]">
              <ExamplePlans />
            </div>
          </div>
        </section>

        <section id="how" className="mx-auto max-w-6xl scroll-mt-16 px-4 py-14 sm:px-8">
          <h2 className="mb-2 text-2xl font-semibold">How it works</h2>
          <p className="mb-8 text-[15px] text-slate-500">From request to doorstep in three steps.</p>
          <ol className="grid gap-4 md:grid-cols-3">
            {STEPS.map((s, i) => (
              <li key={s.title} className="card px-6 py-6">
                <div className="mb-3 flex h-9 w-9 items-center justify-center rounded-full bg-slate-900 text-sm font-semibold text-white">
                  {i + 1}
                </div>
                <h3 className="mb-1.5 text-base font-semibold">{s.title}</h3>
                <p className="text-sm leading-relaxed text-slate-600">{s.body}</p>
              </li>
            ))}
          </ol>
        </section>

        <section id="vehicles" className="scroll-mt-16 bg-white">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-8">
            <h2 className="mb-2 text-2xl font-semibold">Two kinds of vehicles</h2>
            <p className="mb-8 text-[15px] text-slate-500">
              Each quote checks weight, terrain and range, and tells you why an option is not available.
            </p>
            <div className="grid gap-4 md:grid-cols-2">
              {VEHICLES.map((v) => (
                <div key={v.name} className="card px-6 py-6">
                  <div className="mb-4 flex items-center gap-3">
                    <div className="flex h-11 w-11 items-center justify-center rounded-lg bg-slate-100">
                      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke={v.color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                        {v.icon}
                      </svg>
                    </div>
                    <h3 className="text-lg font-semibold">{v.name}</h3>
                  </div>
                  <ul className="space-y-2.5">
                    {v.facts.map((f) => (
                      <li key={f} className="flex gap-2.5 text-sm text-slate-700">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={v.color} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="mt-px flex-none" aria-hidden="true">
                          <path d="M5 12.5l4.5 4.5L19 7.5" />
                        </svg>
                        {f}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section id="coverage" className="mx-auto max-w-6xl scroll-mt-16 px-4 py-14 sm:px-8">
          <h2 className="mb-2 text-2xl font-semibold">Where we deliver</h2>
          <p className="mb-8 text-[15px] text-slate-500">Three stations cover the city. Every delivery starts from one of them.</p>
          <CoverageMap />
        </section>

        <section className="mx-auto max-w-6xl px-4 pb-16 sm:px-8">
          <div className="flex flex-wrap items-center justify-between gap-6 rounded-lg bg-slate-900 px-6 py-8 text-white sm:px-10">
            <div>
              <h2 className="mb-1 text-xl font-semibold">Ready to send something?</h2>
              <p className="text-[15px] text-slate-300">It takes about a minute to set up a delivery.</p>
            </div>
            <StartButton className="btn !bg-white !text-slate-900 hover:!bg-slate-100" />
          </div>
        </section>
      </main>

      <footer className="border-t border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-6 text-sm text-slate-500 sm:px-8">
          <span>SkyLane · Robot and drone delivery in San Francisco</span>
          <div className="flex gap-4">
            <Link href="/login" className="hover:text-slate-900">
              Sign in
            </Link>
            <Link href="/orders" className="hover:text-slate-900">
              My Orders
            </Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
