"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { openStream, type StreamState } from "@/lib/sse";
import { DeliveryMap } from "@/components/map";
import { useStations } from "@/hooks/useStations";
import { ErrorState, LoadingBlock } from "@/components/ui/States";
import type { LiveDevice, OpsOverview } from "@/types/ops";

const STATE_LABEL: Record<string, string> = {
  idle: "Idle",
  on_order: "On order",
  returning: "Returning",
  maintenance: "Maintenance",
  offline: "Offline",
};

/** D32: one SSE stream, a `devices` event every 2 seconds. */
function useLiveDevices() {
  const [devices, setDevices] = useState<LiveDevice[] | null>(null);
  const [state, setState] = useState<StreamState>("connecting");
  useEffect(
    () =>
      openStream("/ops/live", {
        onState: setState,
        onMessage: (m) => {
          if (m.event === "devices") setDevices(JSON.parse(m.data) as LiveDevice[]);
        },
      }),
    [],
  );
  return { devices, state };
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="card px-4 py-3.5">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mono mt-1 text-2xl font-medium">{value}</div>
    </div>
  );
}

export default function OpsOverviewPage() {
  const overview = useQuery({
    queryKey: ["ops", "overview"],
    queryFn: () => api.get<OpsOverview>("/ops/overview"),
    refetchInterval: 10_000,
  });
  const stations = useStations();
  const { devices, state } = useLiveDevices();

  const counts = (devices ?? []).reduce<Record<string, number>>((acc, d) => {
    acc[d.state] = (acc[d.state] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <div className="flex flex-col gap-5">
      {overview.isError && <ErrorState message="Could not load the overview." onRetry={() => overview.refetch()} />}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Orders today" value={overview.data?.orders_today ?? "–"} />
        <Stat label="In progress" value={overview.data?.active_orders ?? "–"} />
        <Stat label="Scheduled" value={overview.data?.scheduled_orders ?? "–"} />
        <Stat label="Devices moving" value={devices ? (counts.on_order ?? 0) + (counts.returning ?? 0) : "–"} />
      </div>

      <div className="flex flex-wrap items-start gap-5">
        <div className="relative h-[360px] min-w-0 flex-[3_1_480px] sm:h-[480px]">
          <DeliveryMap
            className="h-full"
            routes={[]}
            stations={stations.data ?? []}
            stationLabels
            fleet={(devices ?? []).map((d) => ({
              id: d.device_id,
              deviceType: d.device_type,
              lat: d.lat,
              lng: d.lng,
              state: d.state,
              label: `${d.code} · ${STATE_LABEL[d.state] ?? d.state}`,
            }))}
          />
          <span
            className={`absolute bottom-3 left-3 rounded-full px-2.5 py-1 text-xs font-medium ${
              state === "open" ? "bg-emerald-100 text-emerald-800" : "bg-amber-100 text-amber-800"
            }`}
          >
            {state === "open" ? "Live" : state === "failed" ? "Live updates unavailable" : "Connecting…"}
          </span>
        </div>

        <section className="card min-w-0 flex-[2_1_300px] overflow-hidden">
          <h2 className="px-5 pb-2 pt-4 text-base font-semibold">Devices by station</h2>
          {overview.isPending ? (
            <div className="px-5 pb-4">
              <LoadingBlock label="Loading stations" rows={3} />
            </div>
          ) : (
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-5 py-2">Station</th>
                  <th className="px-2 py-2 text-right">Idle</th>
                  <th className="px-2 py-2 text-right">On order</th>
                  <th className="px-2 py-2 text-right">Return</th>
                  <th className="px-5 py-2 text-right">Off</th>
                </tr>
              </thead>
              <tbody>
                {overview.data?.stations.map((s) => (
                  <tr key={s.station_id} className="border-t border-slate-100">
                    <td className="px-5 py-2.5 font-semibold">{s.name}</td>
                    <td className="mono px-2 py-2.5 text-right">{s.idle}</td>
                    <td className="mono px-2 py-2.5 text-right">{s.on_order}</td>
                    <td className="mono px-2 py-2.5 text-right">{s.returning}</td>
                    <td className="mono px-5 py-2.5 text-right">{s.inactive}</td>
                  </tr>
                ))}
                {overview.data?.stations.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-5 py-6 text-center text-slate-500">
                      No devices yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </div>
  );
}
