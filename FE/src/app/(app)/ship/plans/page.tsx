"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { DeliveryMap, type MapRoute } from "@/components/map";
import { etaMinutes, PlanCard, VEHICLE_NAME } from "@/components/plans/PlanCard";
import { toQuoteRequest, useCreateOrder, useQuote, useRecommendation } from "@/hooks/useQuote";
import { useShipmentDraft } from "@/hooks/useShipmentDraft";
import { ApiError } from "@/lib/api";
import { formatPrice, formatTime } from "@/lib/time";
import type { QuoteOption } from "@/types/contract";

type Mode = "best" | "fast" | "cheap";
type Vehicle = "all" | "robot" | "drone";

const DEADLINES: { value: number | null; label: string }[] = [
  { value: null, label: "ASAP" },
  { value: 30, label: "30 min" },
  { value: 60, label: "1 h" },
  { value: 120, label: "2 h" },
];
const MODES: [Mode, string][] = [
  ["best", "Best Match"],
  ["fast", "Fastest"],
  ["cheap", "Lowest Cost"],
];
const VEHICLES: [Vehicle, string][] = [
  ["all", "All"],
  ["robot", "Ground Robot"],
  ["drone", "Drone"],
];

// Available plans first, then the ones the backend ruled out.
function sortOptions(options: QuoteOption[], mode: Mode) {
  const time = (o: QuoteOption) => (o.eta_at ? new Date(o.eta_at).getTime() : Infinity);
  const price = (o: QuoteOption) => o.price_cents ?? Infinity;
  const cmp = {
    best: (a: QuoteOption, b: QuoteOption) =>
      Number(b.tags.includes("recommended")) - Number(a.tags.includes("recommended")) || time(a) - time(b),
    fast: (a: QuoteOption, b: QuoteOption) => time(a) - time(b),
    cheap: (a: QuoteOption, b: QuoteOption) => price(a) - price(b),
  }[mode];
  return [...options].sort((a, b) => Number(b.available) - Number(a.available) || cmp(a, b));
}

function Seg<T>({ label, items, value, onPick }: { label: string; items: [T, string][]; value: T; onPick: (v: T) => void }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-[13px] font-semibold">{label}</span>
      <div className="seg" role="group" aria-label={label}>
        {items.map(([v, name]) => (
          <button key={name} type="button" className={v === value ? "on" : ""} aria-pressed={v === value} onClick={() => onPick(v)}>
            {name}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function PlansPage() {
  const router = useRouter();
  const { draft, hydrated, save } = useShipmentDraft();
  const [mode, setMode] = useState<Mode>("best");
  const [vehicle, setVehicle] = useState<Vehicle>("all");
  const [notice, setNotice] = useState("");
  // Options that POST /orders turned down after the quote: optionId -> error code.
  const [blocked, setBlocked] = useState<Record<string, string>>({});
  const leaving = useRef(false);

  const form = draft?.form;
  const body = useMemo(() => (form ? toQuoteRequest(form) : null), [form]);
  const quote = useQuote(body);
  const createOrder = useCreateOrder();
  const data = quote.data;
  const recommendation = useRecommendation(data);
  const quotedAt = quote.dataUpdatedAt;

  // Nothing to compare without a filled-in request.
  useEffect(() => {
    if (hydrated && !body && !leaving.current) router.replace("/ship");
  }, [hydrated, body, router]);

  // The addresses are wrong: go back to the form, which marks both fields.
  const quoteCode = quote.error instanceof ApiError ? quote.error.code : null;
  useEffect(() => {
    if (!draft || (quoteCode !== "OUT_OF_SERVICE_AREA" && quoteCode !== "SAME_ADDRESS")) return;
    leaving.current = true;
    save({ form: draft.form, address_error: quoteCode });
    router.replace("/ship");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quoteCode]);

  // A quote lives for 10 minutes; fetch a fresh one when it runs out.
  const { refetch } = quote;
  useEffect(() => {
    if (!data) return;
    const ms = new Date(data.expires_at).getTime() - Date.now();
    const t = setTimeout(() => {
      setNotice("Options updated");
      void refetch();
    }, Math.max(0, ms));
    return () => clearTimeout(t);
  }, [data, refetch]);

  // New quote, new option ids: the old "taken" marks no longer apply.
  const quoteId = data?.quote_id;
  useEffect(() => setBlocked({}), [quoteId]);

  const options = useMemo(
    () =>
      (data?.options ?? []).map((o) =>
        blocked[o.option_id]
          ? { ...o, available: false, unavailable_reason: blocked[o.option_id], tags: [] }
          : recommendation.data
            ? { ...o, tags: [...o.tags.filter(t => t !== "recommended"), ...(o.option_id === recommendation.data.option_id ? ["recommended" as const] : [])] }
            : o,
      ),
    [data, blocked, recommendation.data],
  );
  const sorted = useMemo(() => sortOptions(options, mode), [options, mode]);
  const visible = sorted.filter((o) => vehicle === "all" || o.device_type === vehicle);
  const anyAvailable = options.some((o) => o.available);
  const selected = options.find((o) => o.option_id === draft?.option_id && o.available) ?? null;

  const activeId = selected?.option_id ?? options.find((o) => o.tags.includes("recommended"))?.option_id;
  const routes = useMemo<MapRoute[]>(
    () =>
      visible
        .filter((o) => o.available && o.route_points?.length)
        .map((o) => ({ id: o.option_id, deviceType: o.device_type, points: o.route_points!, active: o.option_id === activeId })),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- visible is derived from these
    [sorted, vehicle, activeId],
  );
  const stations = useMemo(() => {
    const byId = new Map(options.map((o) => [o.station.id, o.station]));
    return Array.from(byId.values());
  }, [options]);

  if (!hydrated || !form || !form.pickup || !form.dropoff) return null;

  const setDeadline = (value: number | null) => {
    if (!draft) return;
    setNotice("");
    save({ form: { ...draft.form, arrive_within: value } });
  };

  const select = (o: QuoteOption) => {
    if (!draft || !data) return;
    if (draft.option_id === o.option_id) {
      save({ form: draft.form });
      return;
    }
    // One key per chosen plan: retrying the same plan cannot create a second order (D13).
    save({ form: draft.form, quote_id: data.quote_id, option_id: o.option_id, idempotency_key: crypto.randomUUID() });
    createOrder.reset();
  };

  const placeOrder = () => {
    if (!draft || !data || !selected || !draft.idempotency_key) return;
    createOrder.mutate(
      {
        quote_id: data.quote_id,
        option_id: selected.option_id,
        recipient: { name: draft.form.recipient_name.trim(), phone: draft.form.recipient_phone.trim() },
        idempotency_key: draft.idempotency_key,
      },
      {
        onSuccess: (order) => {
          leaving.current = true;
          router.push(`/orders/${order.order_id}/track`);
          save(null);
        },
        onError: (e) => {
          const code = e instanceof ApiError ? e.code : null;
          if (code === "QUOTE_EXPIRED" || code === "QUOTE_STALE" || code === "OPTION_NOT_FOUND") {
            setNotice("Options updated");
            save({ form: draft.form });
            void refetch();
          } else if (code === "DEADLINE_MISSED" || code === "DEVICE_UNAVAILABLE") {
            setBlocked((b) => ({ ...b, [selected.option_id]: code }));
            save({ form: draft.form });
          }
        },
      },
    );
  };

  const orderCode = createOrder.error instanceof ApiError ? createOrder.error.code : null;
  const handled = ["QUOTE_EXPIRED", "QUOTE_STALE", "OPTION_NOT_FOUND", "DEADLINE_MISSED", "DEVICE_UNAVAILABLE"];
  const allMissedDeadline = options.length > 0 && options.every((o) => o.unavailable_reason === "DEADLINE_MISSED");
  const longerDeadlines = DEADLINES.filter((d) => d.value === null || d.value > (form.arrive_within ?? 0));

  return (
    <div className="mx-auto max-w-[1240px]">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <div className="mb-1 text-[13px] text-slate-500">
            <Link href="/ship" className="font-semibold text-indigo-600">
              Request
            </Link>{" "}
            · Step 2 of 2 · Choose a plan
          </div>
          <h1 className="mb-1 text-2xl font-semibold">Delivery options</h1>
          <div className="text-sm text-slate-600">
            {form.pickup.text} to {form.dropoff.text} · {form.description}, {form.weight} kg
            {form.fragile ? ", fragile" : ""} · {form.recipient_name}
          </div>
        </div>
        <Seg label="Arrive within" items={DEADLINES.map((d) => [d.value, d.label])} value={form.arrive_within} onPick={setDeadline} />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        <Seg label="Optimize for" items={MODES} value={mode} onPick={setMode} />
        <Seg label="Vehicle" items={VEHICLES} value={vehicle} onPick={setVehicle} />
      </div>

      <div className="flex flex-wrap items-start gap-5">
        <div className="h-[420px] min-w-0 flex-[3_1_520px] lg:h-[700px]">
          <DeliveryMap className="h-full" routes={routes} stations={stations} pickup={form.pickup} dropoff={form.dropoff} legend />
        </div>

        <section className="flex min-w-0 flex-[2_1_380px] flex-col gap-3">
          {data && anyAvailable && recommendation.isPending && (
            <div role="status" className="rounded-lg bg-indigo-50 px-4 py-3 text-sm text-indigo-800">Finding your recommended plan…</div>
          )}
          {data && recommendation.isError && (
            <div role="status" className="rounded-xl bg-[#F0F1F5] px-4 py-3 text-sm text-[#4B5263]">
              Recommendation unavailable. You can still compare and select any plan.
              <button type="button" className="ghost ml-2" onClick={() => recommendation.refetch()}>Retry recommendation</button>
            </div>
          )}
          {notice && (
            <div role="status" className="rounded-lg border border-sky-200 bg-sky-50 px-3.5 py-3 text-sm text-sky-900">
              {notice}
            </div>
          )}

          {quote.isPending && body && (
            <div aria-label="Loading delivery options" className="flex flex-col gap-3">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-40 animate-pulse rounded-lg bg-slate-100" />
              ))}
            </div>
          )}

          {quote.isError && !quoteCode?.match(/OUT_OF_SERVICE_AREA|SAME_ADDRESS/) && (
            <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3.5 text-sm text-amber-900">
              <div className="mb-3">Could not load delivery options.</div>
              <button type="button" className="ghost" onClick={() => refetch()}>
                Try again
              </button>
            </div>
          )}

          {data && !anyAvailable && (
            <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3.5 text-sm leading-normal text-amber-900">
              <div className="mb-1 font-semibold">
                {allMissedDeadline ? `No delivery can arrive within ${form.arrive_within} minutes` : "No delivery option is available"}
              </div>
              {allMissedDeadline ? "The limiting constraint is your time window." : "Try a lighter package or different addresses."}
              <div className="mt-3 flex flex-wrap gap-2">
                {allMissedDeadline &&
                  longerDeadlines.map((d) => (
                    <button key={d.label} type="button" className="ghost" onClick={() => setDeadline(d.value)}>
                      {d.value === null ? "Remove time limit" : `Extend to ${d.label}`}
                    </button>
                  ))}
                <Link href="/ship" className="ghost">
                  Edit request
                </Link>
              </div>
            </div>
          )}

          {data && anyAvailable && !visible.some((o) => o.available) && (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-5 text-center text-sm text-slate-600">
              No {vehicle === "robot" ? "ground robot" : "drone"} option is available.{" "}
              <button type="button" className="ghost ml-1.5" onClick={() => setVehicle("all")}>
                Show all vehicles
              </button>
            </div>
          )}

          {data &&
            visible.map((o) => (
              <PlanCard
                key={o.option_id}
                option={o}
                quotedAt={quotedAt}
                recommendationReason={o.available && o.option_id === recommendation.data?.option_id ? recommendation.data.reason : undefined}
                selected={o.option_id === selected?.option_id}
                onSelect={() => select(o)}
              />
            ))}

          <div className="card sticky bottom-3 px-4 py-3.5 shadow-[0_6px_20px_rgba(20,23,31,.12)]">
            {selected ? (
              <div className="mb-2.5 flex items-center justify-between gap-3">
                <div>
                  <div className="text-base font-semibold">
                    {VEHICLE_NAME[selected.device_type]} · {selected.station.name}
                  </div>
                  <div className="text-[13px] text-slate-600">
                    To {form.recipient_name} · Arrives {selected.eta_at && formatTime(selected.eta_at)} ·{" "}
                    {etaMinutes(selected, quotedAt)} min
                  </div>
                </div>
                <div className="mono text-[22px] font-medium">{selected.price_cents !== null && formatPrice(selected.price_cents)}</div>
              </div>
            ) : null}
            <button type="button" className="btn w-full" disabled={!selected || createOrder.isPending} onClick={placeOrder}>
              {createOrder.isPending ? "Placing order…" : selected ? "Place order" : "Select a plan to continue"}
            </button>
            {createOrder.isError && !handled.includes(orderCode ?? "") && (
              <div role="alert" className="err mt-2 text-center">
                Could not place the order. Try again.
              </div>
            )}
            <div className="mt-2 text-center text-xs text-slate-500">
              Prices are estimates. Availability is confirmed when you place the order.
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
