"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import type { StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { DeviceType, LatLngTuple } from "@/types/contract";
import { arcPath } from "./geometry";

const STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
// Used when the tile service is unreachable, so routes and markers still show during a demo.
const FALLBACK_STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#E9ECF1" } }],
};
const SF_CENTER: [number, number] = [-122.42, 37.78];
const COLOR: Record<DeviceType, string> = { robot: "#4338CA", drone: "#C2410C" };
const DEVICE_MOVE_MS = 1000;

export interface MapRoute {
  id: string;
  deviceType: DeviceType;
  points: LatLngTuple[];
  active?: boolean;
}

export interface DeliveryMapProps {
  routes: MapRoute[];
  stations?: { id: string; name: string; lat: number; lng: number }[];
  pickup: { lat: number; lng: number };
  dropoff: { lat: number; lng: number };
  /** Live device position, for tracking. */
  device?: { deviceType: DeviceType; lat: number; lng: number } | null;
  legend?: boolean;
  className?: string;
}

const lngLat = (p: LatLngTuple): [number, number] => [p[1], p[0]];

function pin(label: string, bg: string): HTMLElement {
  const el = document.createElement("div");
  el.textContent = label;
  el.style.cssText = `width:30px;height:30px;border-radius:50%;background:${bg};border:3px solid #fff;color:#fff;font:700 14px/24px inherit;text-align:center;box-shadow:0 1px 3px rgba(20,23,31,.3)`;
  return el;
}

function stationPin(name: string): HTMLElement {
  const el = document.createElement("div");
  el.title = name;
  el.setAttribute("aria-label", name);
  el.style.cssText = "width:14px;height:14px;border-radius:4px;background:#14171F;border:2px solid #fff";
  return el;
}

const ICON: Record<DeviceType, string> = {
  robot: '<rect x="4" y="9" width="16" height="9" rx="2"/><circle cx="8" cy="20" r="1.5"/><circle cx="16" cy="20" r="1.5"/><path d="M12 9V5M10 5h4"/>',
  drone: '<circle cx="12" cy="12" r="2.5"/><path d="M9.5 9.5L5 5M14.5 9.5L19 5M9.5 14.5L5 19M14.5 14.5L19 19"/><circle cx="4" cy="4" r="2"/><circle cx="20" cy="4" r="2"/><circle cx="4" cy="20" r="2"/><circle cx="20" cy="20" r="2"/>',
};

function devicePin(type: DeviceType): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = `width:36px;height:36px;border-radius:50%;background:#fff;border:2px solid ${COLOR[type]};display:flex;align-items:center;justify-content:center;box-shadow:0 2px 6px rgba(20,23,31,.3)`;
  // Static markup from ICON above, never user input.
  el.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="${COLOR[type]}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[type]}</svg>`;
  return el;
}

/** MapLibre + OpenFreeMap map shared by /ship/plans and the tracking page. Import through ./index (client only). */
export default function DeliveryMap({ routes, stations, pickup, dropoff, device, legend, className }: DeliveryMapProps) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let canceled = false;
    let map: maplibregl.Map | null = null;
    void fetch(STYLE_URL)
      .then((r) => (r.ok ? (r.json() as Promise<StyleSpecification>) : FALLBACK_STYLE))
      .catch(() => FALLBACK_STYLE)
      .then((style) => {
        if (canceled || !box.current) return;
        map = new maplibregl.Map({ container: box.current, style, center: SF_CENTER, zoom: 12 });
        mapRef.current = map;
        map.on("load", () => {
          const m = map!;
          m.addSource("routes", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
          const paint = {
            "line-color": ["get", "color"],
            "line-width": ["case", ["get", "active"], 6, 4],
            "line-opacity": ["case", ["get", "active"], 1, 0.4],
          } as maplibregl.LineLayerSpecification["paint"];
          const layout = { "line-cap": "round", "line-join": "round", "line-sort-key": ["case", ["get", "active"], 1, 0] } as maplibregl.LineLayerSpecification["layout"];
          m.addLayer({ id: "robot-routes", type: "line", source: "routes", filter: ["==", ["get", "kind"], "robot"], layout, paint });
          m.addLayer({
            id: "drone-routes",
            type: "line",
            source: "routes",
            filter: ["==", ["get", "kind"], "drone"],
            layout,
            paint: { ...paint, "line-dasharray": [0.5, 2] },
          });
          setReady(true);
        });
      });
    return () => {
      canceled = true;
      map?.remove();
      mapRef.current = null;
      setReady(false);
    };
  }, []);

  // Routes
  useEffect(() => {
    if (!ready || !mapRef.current) return;
    const source = mapRef.current.getSource("routes") as maplibregl.GeoJSONSource;
    source.setData({
      type: "FeatureCollection",
      features: routes
        .filter((r) => r.points.length > 1)
        .map((r) => ({
          type: "Feature",
          properties: { kind: r.deviceType, color: COLOR[r.deviceType], active: !!r.active },
          geometry: {
            type: "LineString",
            coordinates: (r.deviceType === "drone" ? arcPath(r.points) : r.points).map(lngLat),
          },
        })),
    });
  }, [ready, routes]);

  // Fixed markers
  const stationsKey = JSON.stringify(stations ?? []);
  const placesKey = `${pickup.lat},${pickup.lng},${dropoff.lat},${dropoff.lng}`;
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) return;
    const list = JSON.parse(stationsKey) as NonNullable<DeliveryMapProps["stations"]>;
    const markers = [
      ...list.map((s) => new maplibregl.Marker({ element: stationPin(s.name) }).setLngLat([s.lng, s.lat])),
      new maplibregl.Marker({ element: pin("P", "#14171F") }).setLngLat([pickup.lng, pickup.lat]),
      new maplibregl.Marker({ element: pin("D", "#4338CA") }).setLngLat([dropoff.lng, dropoff.lat]),
    ];
    markers.forEach((m) => m.addTo(map));
    return () => markers.forEach((m) => m.remove());
    // eslint-disable-next-line react-hooks/exhaustive-deps -- placesKey stands in for pickup and dropoff
  }, [ready, stationsKey, placesKey]);

  // Frame everything once per set of places; later route changes must not move the user's view.
  const routesRef = useRef(routes);
  routesRef.current = routes;
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) return;
    const bounds = new maplibregl.LngLatBounds();
    bounds.extend([pickup.lng, pickup.lat]);
    bounds.extend([dropoff.lng, dropoff.lat]);
    (JSON.parse(stationsKey) as { lat: number; lng: number }[]).forEach((s) => bounds.extend([s.lng, s.lat]));
    routesRef.current.forEach((r) => r.points.forEach((p) => bounds.extend(lngLat(p))));
    map.fitBounds(bounds, { padding: 70, maxZoom: 15, duration: 0 });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- placesKey stands in for pickup and dropoff
  }, [ready, placesKey, stationsKey]);

  // Live device: glide between the once-a-second updates instead of jumping.
  const deviceMarker = useRef<maplibregl.Marker | null>(null);
  const deviceType = device?.deviceType;
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map || !deviceType) return;
    const marker = new maplibregl.Marker({ element: devicePin(deviceType) }).setLngLat(map.getCenter());
    deviceMarker.current = marker;
    return () => {
      marker.remove();
      deviceMarker.current = null;
    };
  }, [ready, deviceType]);

  const lat = device?.lat;
  const lng = device?.lng;
  useEffect(() => {
    const map = mapRef.current;
    const marker = deviceMarker.current;
    if (!map || !marker || lat === undefined || lng === undefined) return;
    if (!marker.getElement().isConnected) {
      marker.setLngLat([lng, lat]).addTo(map);
      return;
    }
    const from = marker.getLngLat();
    const start = performance.now();
    let frame = requestAnimationFrame(function step(now) {
      const t = Math.min(1, (now - start) / DEVICE_MOVE_MS);
      marker.setLngLat([from.lng + (lng - from.lng) * t, from.lat + (lat - from.lat) * t]);
      if (t < 1) frame = requestAnimationFrame(step);
    });
    return () => cancelAnimationFrame(frame);
  }, [ready, deviceType, lat, lng]);

  return (
    <div className={`relative overflow-hidden rounded-[14px] border border-[#E3E5EB] bg-[#E9ECF1] ${className ?? ""}`}>
      <div ref={box} style={{ position: "absolute", inset: 0 }} role="img" aria-label="Map of San Francisco with the delivery routes" />
      {legend && (
        <div className="absolute left-3.5 top-3.5 rounded-[10px] border border-[#CDD1DB] bg-white px-3 py-2 text-xs leading-relaxed shadow-sm">
          <div className="mb-0.5 font-bold">Legend</div>
          <div className="flex items-center gap-2">
            <svg width="26" height="6" aria-hidden="true">
              <path d="M0 3H26" stroke={COLOR.robot} strokeWidth="4" />
            </svg>
            Ground robot
          </div>
          <div className="flex items-center gap-2">
            <svg width="26" height="6" aria-hidden="true">
              <path d="M0 3H26" stroke={COLOR.drone} strokeWidth="4" strokeDasharray="2 4" strokeLinecap="round" />
            </svg>
            Drone
          </div>
          <div className="mt-0.5 flex items-center gap-2">
            <span className="inline-block h-2.5 w-2.5 rounded-[3px] bg-[#14171F]" />
            Station
          </div>
        </div>
      )}
      <div className="absolute right-3.5 top-3.5 flex flex-col overflow-hidden rounded-[10px] border border-[#CDD1DB] shadow-sm">
        <button
          type="button"
          aria-label="Zoom in"
          onClick={() => mapRef.current?.zoomIn()}
          className="h-11 w-11 bg-white text-xl text-[#2A2F3C] hover:bg-[#F0F1F5]"
        >
          +
        </button>
        <button
          type="button"
          aria-label="Zoom out"
          onClick={() => mapRef.current?.zoomOut()}
          className="h-11 w-11 border-t border-[#E3E5EB] bg-white text-xl text-[#2A2F3C] hover:bg-[#F0F1F5]"
        >
          −
        </button>
      </div>
    </div>
  );
}
