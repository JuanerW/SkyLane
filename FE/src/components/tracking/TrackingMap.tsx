"use client";

import { useMemo } from "react";
import { arcPath, DeliveryMap, pointAlong } from "@/components/map";
import type { DeviceType, LatLngTuple, PositionEvent } from "@/types/contract";

export interface TrackingMapProps {
  route: LatLngTuple[];
  deviceType: DeviceType;
  station: { lat: number; lng: number } | null;
  pickup: { lat: number; lng: number };
  dropoff: { lat: number; lng: number };
  position: PositionEvent | null;
}

export function TrackingMap({ route, deviceType, station, pickup, dropoff, position }: TrackingMapProps) {
  const routes = useMemo(() => [{ id: "order", deviceType, points: route, active: true }], [deviceType, route]);
  const stations = useMemo(() => (station ? [{ id: "station", name: "Station", ...station }] : []), [station]);
  const arc = useMemo(() => (deviceType === "drone" ? arcPath(route) : null), [deviceType, route]);

  // The drone is drawn on an arc (D18), so place it by progress; the robot uses the reported point.
  const at = position && arc ? pointAlong(arc, position.progress) : position && [position.lat, position.lng];

  return (
    <DeliveryMap
      className="h-[420px]"
      routes={routes}
      stations={stations}
      pickup={pickup}
      dropoff={dropoff}
      device={at ? { deviceType, lat: at[0], lng: at[1] } : null}
    />
  );
}
