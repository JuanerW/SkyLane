"use client";

import type { DeviceType, LatLngTuple, PositionEvent } from "@/types/contract";

export interface TrackingMapProps {
  route: LatLngTuple[];
  deviceType: DeviceType;
  station: { lat: number; lng: number } | null;
  pickup: { lat: number; lng: number };
  dropoff: { lat: number; lng: number };
  position: PositionEvent | null;
}

/**
 * PLACEHOLDER until Pei's map component (components/map/) lands.
 * Swap the body for <DeliveryMap {...props} />; the props above are what the tracking page passes.
 * For now: a plain SVG of the route with the device dot, so the page is usable with mock data.
 */
export function TrackingMap({ route, deviceType, pickup, dropoff, position }: TrackingMapProps) {
  const pts = [...route, [pickup.lat, pickup.lng], [dropoff.lat, dropoff.lng]] as LatLngTuple[];
  if (position) pts.push([position.lat, position.lng]);
  const lats = pts.map((p) => p[0]);
  const lngs = pts.map((p) => p[1]);
  const [minLat, maxLat, minLng, maxLng] = [Math.min(...lats), Math.max(...lats), Math.min(...lngs), Math.max(...lngs)];
  const pad = 20;
  const W = 600;
  const H = 320;
  const sx = (lng: number) => pad + ((lng - minLng) / (maxLng - minLng || 1)) * (W - 2 * pad);
  const sy = (lat: number) => H - pad - ((lat - minLat) / (maxLat - minLat || 1)) * (H - 2 * pad);
  const d = route.map((p, i) => `${i ? "L" : "M"}${sx(p[1]).toFixed(1)},${sy(p[0]).toFixed(1)}`).join(" ");

  return (
    <div className="relative overflow-hidden rounded-lg border border-slate-200 bg-slate-100">
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label="Delivery route">
        <path
          d={d}
          fill="none"
          stroke="#6366f1"
          strokeWidth={3}
          strokeDasharray={deviceType === "drone" ? "8 6" : undefined}
        />
        <circle cx={sx(pickup.lng)} cy={sy(pickup.lat)} r={6} fill="#0ea5e9" />
        <circle cx={sx(dropoff.lng)} cy={sy(dropoff.lat)} r={6} fill="#10b981" />
        {position && <circle cx={sx(position.lng)} cy={sy(position.lat)} r={8} fill="#111827" stroke="#fff" strokeWidth={2} />}
      </svg>
      <span className="absolute bottom-2 right-2 rounded bg-white/80 px-2 py-0.5 text-xs text-slate-500">
        Map placeholder — waiting for components/map
      </span>
    </div>
  );
}
