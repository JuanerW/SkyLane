"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { useStations } from "@/hooks/useStations";
import { useAuth } from "@/hooks/useAuth";
import { formatDateTime, formatTime } from "@/lib/time";
import { EmptyState, ErrorState, LoadingBlock } from "@/components/ui/States";
import type { Booking, OperatorRef, OpsDevice } from "@/types/ops";

const STATE_CLS: Record<string, string> = {
  idle: "bg-slate-100 text-slate-700",
  on_order: "bg-indigo-100 text-indigo-800",
  returning: "bg-sky-100 text-sky-800",
  maintenance: "bg-amber-100 text-amber-800",
  offline: "bg-rose-100 text-rose-800",
};

function errorText(e: unknown) {
  if (e instanceof ApiError) {
    if (e.code === "DEVICE_HAS_BOOKINGS") return `${e.message}.`;
    if (e.code === "DEVICE_CODE_TAKEN") return "Another device already uses this code.";
    return e.message;
  }
  return "Something went wrong. Try again.";
}

function Schedule({ id }: { id: string }) {
  const q = useQuery({ queryKey: ["ops", "schedule", id], queryFn: () => api.get<Booking[]>(`/ops/devices/${id}/schedule`) });
  if (q.isPending) return <LoadingBlock label="Loading schedule" rows={1} />;
  if (q.isError) return <ErrorState message="Could not load the schedule." onRetry={() => q.refetch()} />;
  if (!q.data.length) return <p className="text-sm text-slate-500">Nothing booked today or later.</p>;
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {q.data.map((b, i) => (
        <li key={i} className="flex flex-wrap gap-x-3">
          <span className="mono">
            {formatDateTime(b.starts_at)} – {formatTime(b.ends_at)}
          </span>
          <span className="capitalize text-slate-600">{b.kind}</span>
          {b.order_id && <span className="mono text-slate-500">#{b.order_id.slice(0, 8)}</span>}
        </li>
      ))}
    </ul>
  );
}

function AddDevice({ onDone }: { onDone: () => void }) {
  const stations = useStations();
  const { user } = useAuth();
  const operators = useQuery({
    queryKey: ["admin", "operators"],
    queryFn: () => api.get<OperatorRef[]>("/admin/operators"),
    enabled: user?.role === "admin",
  });
  const [code, setCode] = useState("");
  const [station, setStation] = useState("");
  const [type, setType] = useState<"robot" | "drone">("robot");
  const [operator, setOperator] = useState("");
  const create = useMutation({
    mutationFn: () =>
      api.post<OpsDevice>("/ops/devices", { code, station_id: station, device_type: type, operator_id: operator || undefined }),
    onSuccess: () => {
      setCode("");
      onDone();
    },
  });

  return (
    <form
      className="card mb-4 flex flex-wrap items-end gap-3 px-4 py-4"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate();
      }}
    >
      <label className="flex min-w-[140px] flex-1 flex-col gap-1 text-sm">
        <span className="lbl">Code</span>
        <input className="inp" required maxLength={40} value={code} onChange={(e) => setCode(e.target.value)} placeholder="R-120" />
      </label>
      <label className="flex min-w-[160px] flex-1 flex-col gap-1 text-sm">
        <span className="lbl">Station</span>
        <select className="inp" required value={station} onChange={(e) => setStation(e.target.value)}>
          <option value="">Choose…</option>
          {stations.data?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      <label className="flex min-w-[120px] flex-col gap-1 text-sm">
        <span className="lbl">Type</span>
        <select className="inp" value={type} onChange={(e) => setType(e.target.value as "robot" | "drone")}>
          <option value="robot">Ground robot</option>
          <option value="drone">Drone</option>
        </select>
      </label>
      {user?.role === "admin" && (
        <label className="flex min-w-[160px] flex-1 flex-col gap-1 text-sm">
          <span className="lbl">Operator</span>
          <select className="inp" value={operator} onChange={(e) => setOperator(e.target.value)}>
            <option value="">Platform (none)</option>
            {operators.data?.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <button type="submit" className="btn !h-11 !text-sm" disabled={create.isPending}>
        {create.isPending ? "Adding…" : "Add device"}
      </button>
      {create.isError && <div className="err w-full">{errorText(create.error)}</div>}
    </form>
  );
}

function DeviceRowItem({ d }: { d: OpsDevice }) {
  const qc = useQueryClient();
  const stations = useStations();
  const [open, setOpen] = useState(false);
  const patch = useMutation({
    mutationFn: (body: { status?: string; station_id?: string }) => api.patch<OpsDevice>(`/ops/devices/${d.device_id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ops"] }),
  });

  return (
    <li className="card px-4 py-3.5">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-[140px] flex-1">
          <div className="mono font-semibold">{d.code}</div>
          <div className="text-xs text-slate-500">
            {d.device_type === "drone" ? "Drone" : "Ground robot"} · {d.station_name}
          </div>
        </div>
        <span className={`badge ${STATE_CLS[d.state] ?? ""}`}>{d.state.replace("_", " ")}</span>
        <span className="text-sm text-slate-600">{d.future_bookings} upcoming</span>
        <select
          aria-label={`Status of ${d.code}`}
          className="inp !h-9 !w-auto"
          value={d.status}
          disabled={patch.isPending}
          onChange={(e) => patch.mutate({ status: e.target.value })}
        >
          <option value="active">Active</option>
          <option value="maintenance">Maintenance</option>
          <option value="offline">Offline</option>
        </select>
        <select
          aria-label={`Station of ${d.code}`}
          className="inp !h-9 !w-auto"
          value={d.station_id}
          disabled={patch.isPending}
          onChange={(e) => patch.mutate({ station_id: e.target.value })}
        >
          {stations.data?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        <button type="button" className="ghost !h-9" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          {open ? "Hide schedule" : "Schedule"}
        </button>
      </div>
      {patch.isError && <div className="err mt-2">{errorText(patch.error)}</div>}
      {open && (
        <div className="mt-3 border-t border-slate-100 pt-3">
          <Schedule id={d.device_id} />
        </div>
      )}
    </li>
  );
}

export default function OpsDevicesPage() {
  const qc = useQueryClient();
  const devices = useQuery({ queryKey: ["ops", "devices"], queryFn: () => api.get<OpsDevice[]>("/ops/devices"), refetchInterval: 15_000 });
  const [adding, setAdding] = useState(false);

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-3">
        <p className="text-sm text-slate-600">Devices are never deleted; set them to maintenance or offline instead.</p>
        <button type="button" className="ghost" onClick={() => setAdding((a) => !a)}>
          {adding ? "Close" : "Add device"}
        </button>
      </div>
      {adding && (
        <AddDevice
          onDone={() => {
            setAdding(false);
            qc.invalidateQueries({ queryKey: ["ops"] });
          }}
        />
      )}
      {devices.isPending && <LoadingBlock label="Loading devices" rows={5} />}
      {devices.isError && <ErrorState message="Could not load devices." onRetry={() => devices.refetch()} />}
      {devices.data?.length === 0 && <EmptyState title="No devices yet" body="Add your first robot or drone above." />}
      {!!devices.data?.length && (
        <ul className="flex flex-col gap-3">
          {devices.data.map((d) => (
            <DeviceRowItem key={d.device_id} d={d} />
          ))}
        </ul>
      )}
    </div>
  );
}
