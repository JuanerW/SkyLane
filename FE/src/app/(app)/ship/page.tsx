"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AddressAutocomplete } from "@/components/ship/AddressAutocomplete";
import { RecentActivity } from "@/components/ship/RecentActivity";
import { EMPTY_FORM, useShipmentDraft, type ShipmentDraft, type ShipmentForm } from "@/hooks/useShipmentDraft";

const ARRIVE_WITHIN:{ value: number | null; label: string }[] = [
  { value: null, label: "ASAP" },
  { value: 30, label: "30 min" },
  { value: 60, label: "1 hour" },
  { value: 120, label: "2 hours" },
];

/** Design 8.4 (D39): at most 7 days ahead, 08:00-20:00. Mirrors skylane.booking on the server. */
function pickupError(v: string | null | undefined): string | null {
  if (!v) return null;
  const t = new Date(v);
  if (Number.isNaN(t.getTime())) return "Choose a pickup time.";
  if (t.getTime() < Date.now() - 60_000) return "Pickup time is in the past.";
  if (t.getTime() > Date.now() + 7 * 24 * 3600_000) return "Book at most 7 days ahead.";
  const minutes = t.getHours() * 60 + t.getMinutes();
  if (minutes < 8 * 60 || minutes > 20 * 60) return "Pickups run from 8:00 to 20:00.";
  return null;
}

/** "YYYY-MM-DDTHH:mm" in local time, as datetime-local wants. */
function localInput(t: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}T${pad(t.getHours())}:${pad(t.getMinutes())}`;
}

const place = (message: string) =>
  z.object({ text: z.string(), lat: z.number(), lng: z.number() }).nullable().refine((p) => p !== null, { message });

const schema = z
  .object({
    description: z.string().trim().min(1, "Describe what you are sending."),
    weight: z.string().superRefine((v, ctx) => {
      const w = Number(v);
      if (!v.trim()) ctx.addIssue({ code: "custom", message: "Enter a weight." });
      else if (Number.isNaN(w) || w <= 0) ctx.addIssue({ code: "custom", message: "Enter a valid weight." });
      else if (w > 10) ctx.addIssue({ code: "custom", message: "Over the 10 kg limit for robots and drones." });
    }),
    fragile: z.boolean(),
    pickup: place("Choose a pickup address from the suggestions."),
    dropoff: place("Choose a delivery address from the suggestions."),
    recipient_name: z.string().trim().min(1, "Enter the recipient's name."),
    recipient_phone: z.string().trim().min(1, "Enter a phone number for delivery updates."),
    notes: z.string().max(500, "Keep notes under 500 characters."),
    arrive_within: z.number().nullable(),
    pickup_after: z.string().nullable().optional(),
  })
  .superRefine((f, ctx) => {
    const msg = pickupError(f.pickup_after);
    if (msg) ctx.addIssue({ code: "custom", path: ["pickup_after"], message: msg });
  })
  .refine((f) => !f.pickup || !f.dropoff || f.pickup.text !== f.dropoff.text, {
    path: ["dropoff"],
    message: "Pickup and delivery must be different addresses.",
  });

const ADDRESS_ERROR = {
  OUT_OF_SERVICE_AREA: "Only addresses in San Francisco",
  SAME_ADDRESS: "Pickup and delivery must be different addresses.",
};

function ShipForm({ draft, save }: { draft: ShipmentDraft | null; save: (d: ShipmentDraft | null) => void }) {
  const router = useRouter();
  const [toast, setToast] = useState("");
  const [addressError, setAddressError] = useState(draft?.address_error);
  const {
    register,
    control,
    handleSubmit,
    getValues,
    reset,
    formState: { errors, isSubmitted },
  } = useForm<ShipmentForm>({
    // The schema's output is the same shape as the form; only `nullable` differs after refine.
    resolver: zodResolver(schema) as never,
    defaultValues: draft?.form ?? EMPTY_FORM,
  });

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(""), 2400);
    return () => clearTimeout(t);
  }, [toast]);

  const onValid = (form: ShipmentForm) => {
    save({ form });
    router.push("/ship/plans");
  };

  const hasErrors = isSubmitted && Object.keys(errors).length > 0;
  const addrMsg = addressError ? ADDRESS_ERROR[addressError] : undefined;

  return (
    <div className="flex flex-wrap items-start gap-6">
      <form onSubmit={handleSubmit(onValid)} noValidate className="flex min-w-0 flex-[999_1_520px] flex-col gap-4">
        <section className="card px-6 py-[22px]">
          <h2 className="mb-4 text-base font-semibold">Package</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label className="lbl" htmlFor="description">
                Description
              </label>
              <input
                id="description"
                className={`inp ${errors.description ? "bad" : ""}`}
                placeholder="e.g. Signed contracts, phone case"
                {...register("description")}
              />
              {errors.description && <div className="err">{errors.description.message}</div>}
            </div>
            <div>
              <label className="lbl" htmlFor="weight">
                Weight (kg)
              </label>
              <input
                id="weight"
                className={`inp ${errors.weight ? "bad" : ""}`}
                type="number"
                min="0"
                max="10"
                step="0.1"
                placeholder="2.0"
                {...register("weight")}
              />
              {errors.weight && <div className="err">{errors.weight.message}</div>}
            </div>
            <div>
              <span className="lbl">Handling</span>
              <label className="flex h-11 cursor-pointer items-center gap-2 text-sm">
                <input type="checkbox" className="h-4 w-4 accent-slate-900" {...register("fragile")} />
                Fragile
              </label>
            </div>
          </div>
        </section>

        <section className="card px-6 py-[22px]">
          <h2 className="mb-4 text-base font-semibold">Pickup</h2>
          <Controller
            control={control}
            name="pickup"
            render={({ field, fieldState }) => (
              <AddressAutocomplete
                id="pickup"
                label="Pickup address"
                placeholder="Start typing an address in San Francisco"
                value={field.value}
                onChange={(p) => {
                  field.onChange(p);
                  setAddressError(undefined);
                }}
                error={fieldState.error?.message ?? addrMsg}
              />
            )}
          />
        </section>

        <section className="card px-6 py-[22px]">
          <h2 className="mb-4 text-base font-semibold">Delivery</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <Controller
                control={control}
                name="dropoff"
                render={({ field, fieldState }) => (
                  <AddressAutocomplete
                    id="dropoff"
                    label="Recipient's address"
                    placeholder="Start typing an address"
                    value={field.value}
                    onChange={(p) => {
                      field.onChange(p);
                      setAddressError(undefined);
                    }}
                    error={fieldState.error?.message ?? addrMsg}
                  />
                )}
              />
            </div>
            <div>
              <label className="lbl" htmlFor="recipient_name">
                Recipient name
              </label>
              <input
                id="recipient_name"
                className={`inp ${errors.recipient_name ? "bad" : ""}`}
                placeholder="Full name"
                {...register("recipient_name")}
              />
              {errors.recipient_name && <div className="err">{errors.recipient_name.message}</div>}
            </div>
            <div>
              <label className="lbl" htmlFor="recipient_phone">
                Recipient phone
              </label>
              <input
                id="recipient_phone"
                type="tel"
                className={`inp ${errors.recipient_phone ? "bad" : ""}`}
                placeholder="(555) 000-0000"
                {...register("recipient_phone")}
              />
              {errors.recipient_phone && <div className="err">{errors.recipient_phone.message}</div>}
            </div>
            <div className="sm:col-span-2">
              <label className="lbl" htmlFor="notes">
                Notes <span className="font-normal text-slate-500">(optional)</span>
              </label>
              <textarea
                id="notes"
                className={`inp ${errors.notes ? "bad" : ""}`}
                placeholder="Front desk, 12th floor, keep upright"
                {...register("notes")}
              />
              {errors.notes && <div className="err">{errors.notes.message}</div>}
            </div>
            <div className="sm:col-span-2">
              <span className="lbl">Pickup time</span>
              <Controller
                control={control}
                name="pickup_after"
                render={({ field }) => (
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      className={`chip ${!field.value ? "on" : ""}`}
                      aria-pressed={!field.value}
                      onClick={() => field.onChange(null)}
                    >
                      Now
                    </button>
                    <button
                      type="button"
                      className={`chip ${field.value ? "on" : ""}`}
                      aria-pressed={!!field.value}
                      onClick={() => field.value || field.onChange(localInput(new Date(Date.now() + 3600_000)))}
                    >
                      Schedule
                    </button>
                    {field.value && (
                      <input
                        type="datetime-local"
                        aria-label="Pick up after"
                        className={`inp !w-auto ${errors.pickup_after ? "bad" : ""}`}
                        min={localInput(new Date())}
                        max={localInput(new Date(Date.now() + 7 * 24 * 3600_000))}
                        value={field.value}
                        onChange={(e) => field.onChange(e.target.value || null)}
                      />
                    )}
                  </div>
                )}
              />
              {errors.pickup_after && <div className="err">{errors.pickup_after.message}</div>}
            </div>
            <div className="sm:col-span-2">
              <span className="lbl">Arrive within</span>
              <Controller
                control={control}
                name="arrive_within"
                render={({ field }) => (
                  <div className="flex flex-wrap gap-2">
                    {ARRIVE_WITHIN.map((o) => (
                      <button
                        key={o.label}
                        type="button"
                        className={`chip ${field.value === o.value ? "on" : ""}`}
                        aria-pressed={field.value === o.value}
                        onClick={() => field.onChange(o.value)}
                      >
                        {o.label}
                      </button>
                    ))}
                  </div>
                )}
              />
            </div>
          </div>
        </section>

        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" className="btn">
            Find Delivery Options
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
          <button
            type="button"
            className="ghost"
            onClick={() => {
              save({ form: getValues() });
              setToast("Draft saved");
            }}
          >
            Save draft
          </button>
          <button
            type="button"
            className="ghost"
            onClick={() => {
              reset(EMPTY_FORM);
              save(null);
              setAddressError(undefined);
            }}
          >
            Clear form
          </button>
          {toast && (
            <span role="status" className="rounded-lg bg-emerald-50 px-3 py-2 text-[13px] text-emerald-700">
              {toast}
            </span>
          )}
        </div>
        {hasErrors && (
          <div role="alert" className="text-[13px] text-rose-600">
            Complete the highlighted fields to see delivery options.
          </div>
        )}
      </form>

      <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-4">
        <RecentActivity />
      </div>
    </div>
  );
}

export default function ShipPage() {
  const { draft, hydrated, save } = useShipmentDraft();

  return (
    <div className="mx-auto max-w-[1120px]">
      <div className="mb-1.5 text-[13px] text-slate-500">Step 1 of 2 · Request</div>
      <h1 className="mb-1.5 text-2xl font-semibold">
        Where would you like to send a package?
      </h1>
      <p className="mb-6 text-[15px] text-slate-500">
        Tell us what and where. We&apos;ll compare robot and drone options across San Francisco.
      </p>
      {/* The form takes its defaults from the draft, so wait until sessionStorage has been read. */}
      {hydrated && <ShipForm draft={draft} save={save} />}
    </div>
  );
}
