"use client";

import { DeliveryMap } from "@/components/map";
import { useStations } from "@/hooks/useStations";
import { SERVICE_AREA } from "@/lib/serviceArea";

export function CoverageMap() {
  const stations = useStations();

  return (
    <div className="flex flex-wrap items-start gap-5">
      <div className="h-[360px] min-w-0 flex-[3_1_480px] sm:h-[480px]">
        <DeliveryMap className="h-full" routes={[]} stations={stations.data ?? []} stationLabels area={SERVICE_AREA} />
      </div>
      <div className="flex min-w-0 flex-[2_1_280px] flex-col gap-3">
        <div className="card px-5 py-4">
          <h3 className="mb-1 text-base font-semibold">Service area</h3>
          <p className="text-sm leading-relaxed text-slate-600">
            Pickup and delivery addresses both need to be inside the dashed area, which covers San Francisco from the
            Presidio to Hunters Point.
          </p>
        </div>
        <div className="card px-5 pb-2 pt-4">
          <h3 className="mb-1 text-base font-semibold">Stations</h3>
          {stations.isPending && <p className="py-3 text-sm text-slate-500">Loading stations…</p>}
          {stations.isError && (
            <p className="py-3 text-sm text-slate-500">
              Station locations are unavailable right now.{" "}
              <button type="button" className="font-semibold text-indigo-600 underline" onClick={() => stations.refetch()}>
                Retry
              </button>
            </p>
          )}
          {stations.data?.map((s, i) => (
            <div key={s.id} className={`flex items-center gap-3 py-3 ${i ? "border-t border-slate-100" : ""}`}>
              <span className="h-3 w-3 flex-none rounded-sm bg-slate-900" aria-hidden="true" />
              <div>
                <div className="text-sm font-semibold">{s.name}</div>
                <div className="text-xs text-slate-500">Ground robots and drones</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
