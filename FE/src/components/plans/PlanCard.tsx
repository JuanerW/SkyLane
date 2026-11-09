"use client";

import { formatMiles, formatPrice, formatTime } from "@/lib/time";
import type { QuoteOption } from "@/types/contract";

const TAG = {
  recommended: { text: "Recommended", cls: "bg-[#EAEBFB] text-[#3730A3]" },
  fastest: { text: "Fastest", cls: "bg-[#FFEDD5] text-[#9A3412]" },
  cheapest: { text: "Lowest Cost", cls: "bg-[#DCFCE7] text-[#166534]" },
};

export const UNAVAILABLE_TEXT: Record<string, string> = {
  OVERWEIGHT: "Too heavy for this vehicle",
  STEEP_TERRAIN: "Route crosses a steep hill robots can't climb",
  OUT_OF_RANGE: "Beyond the drone's flight range",
  NO_DEVICE_AVAILABLE: "No vehicle available at this station",
  DEADLINE_MISSED: "Can't arrive within your time window",
  DEVICE_UNAVAILABLE: "This vehicle was just taken",
};

export const VEHICLE_NAME = { robot: "Ground Robot", drone: "Drone" };

function VehicleIcon({ type }: { type: QuoteOption["device_type"] }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#14171F" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {type === "robot" ? (
        <>
          <rect x="4" y="9" width="16" height="9" rx="2" />
          <circle cx="8" cy="20" r="1.5" />
          <circle cx="16" cy="20" r="1.5" />
          <path d="M12 9V5M10 5h4" />
        </>
      ) : (
        <>
          <circle cx="12" cy="12" r="2.5" />
          <path d="M9.5 9.5L5 5M14.5 9.5L19 5M9.5 14.5L5 19M14.5 14.5L19 19" />
          <circle cx="4" cy="4" r="2" />
          <circle cx="20" cy="4" r="2" />
          <circle cx="4" cy="20" r="2" />
          <circle cx="20" cy="20" r="2" />
        </>
      )}
    </svg>
  );
}

interface Props {
  option: QuoteOption;
  /** When the quote was received: "minutes away" and "Now" are measured from here. */
  quotedAt: number;
  selected: boolean;
  onSelect: () => void;
  recommendationReason?: string;
  readOnly?: boolean;
}

export function etaMinutes(option: QuoteOption, quotedAt: number) {
  return option.eta_at ? Math.max(1, Math.round((new Date(option.eta_at).getTime() - quotedAt) / 60_000)) : null;
}

export function PlanCard({ option: o, quotedAt, selected, onSelect, recommendationReason, readOnly = false }: Props) {
  const departsNow = !!o.departs_at && new Date(o.departs_at).getTime() - quotedAt < 60_000;

  return (
    <button
      type="button"
      className={`plan ${!o.available ? "dim" : selected ? "sel" : ""}`}
      aria-pressed={selected}
      disabled={readOnly}
      aria-disabled={readOnly || !o.available}
      onClick={!readOnly && o.available ? onSelect : undefined}
    >
      <div className="mb-2.5 flex items-start justify-between gap-2">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 flex-none items-center justify-center rounded-[10px] bg-[#F0F1F5]">
            <VehicleIcon type={o.device_type} />
          </div>
          <div>
            <div className="text-base font-bold">{VEHICLE_NAME[o.device_type]}</div>
            <div className="text-xs text-[#5B6274]">{o.station.name}</div>
          </div>
        </div>
        <div className="flex flex-wrap justify-end gap-1">
          {o.tags.map((t) => (
            <span key={t} className={`badge ${TAG[t].cls}`}>
              {TAG[t].text}
            </span>
          ))}
        </div>
      </div>

      {o.available ? (
        <>
          <div className="flex items-baseline gap-6">
            <div>
              <div className="mono text-2xl font-medium">{etaMinutes(o, quotedAt)} min</div>
              <div className="text-xs text-[#5B6274]">Arrives {o.eta_at && formatTime(o.eta_at)}</div>
            </div>
            <div>
              <div className="mono text-2xl font-medium">{o.price_cents !== null && formatPrice(o.price_cents)}</div>
              <div className="text-xs text-[#5B6274]">Estimated price</div>
            </div>
            <div className="ml-auto text-right text-xs leading-normal text-[#4B5263]">
              {o.distance_m !== null && <div>{formatMiles(o.distance_m)} route</div>}
              <div>{departsNow ? "Departs now" : `Next free ${o.departs_at && formatTime(o.departs_at)}`}</div>
            </div>
          </div>
          {recommendationReason && (
            <p className="mt-3 rounded-lg bg-[#EEF0FF] px-3 py-2 text-left text-[13px] leading-normal text-[#3730A3]">{recommendationReason}</p>
          )}
          {!readOnly && <div
            className={`mt-3 flex h-10 items-center justify-center rounded-[9px] text-sm font-semibold ${
              selected ? "bg-[#4338CA] text-white" : "bg-[#EEF0FF] text-[#3730A3]"
            }`}
          >
            {selected ? "Selected" : "Select plan"}
          </div>}
        </>
      ) : (
        <div className="text-[13px] font-semibold text-[#8A4B00]">
          {(o.unavailable_reason && UNAVAILABLE_TEXT[o.unavailable_reason]) ?? "Not available"}
        </div>
      )}
    </button>
  );
}
