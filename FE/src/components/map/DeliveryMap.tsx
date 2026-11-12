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
  layers: [{ id: "background", type: "background", paint: { "background-color": "#F1F5F9" } }],
};
const SF_CENTER: [number, number] = [-122.42, 37.78];
const COLOR: Record<DeviceType, string> = { robot: "#4F46E5", drone: "#C2410C" };
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
  /** Show station names next to their markers (home page). */
  stationLabels?: boolean;
  pickup?: { lat: number; lng: number };
  dropoff?: { lat: number; lng: number };
  /** Shaded service area (home page). */
  area?: { minLat: number; maxLat: number; minLng: number; maxLng: number };
  /** Live device position, for tracking. */
  device?: { deviceType: DeviceType; lat: number; lng: number } | null;
  /** Many live devices at once (ops overview, design 8.1). Idle ones are drawn faded. */
  fleet?: { id: string; deviceType: DeviceType; lat: number; lng: number; state: string; label?: string }[];
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

function stationPin(name: string, showLabel: boolean): HTMLElement {
  const el = document.createElement("div");
  el.title = name;
  el.setAttribute("aria-label", name);
  el.style.cssText = "display:flex;align-items:center;gap:6px";
  const dot = document.createElement("span");
  dot.style.cssText = "width:14px;height:14px;border-radius:4px;background:#0F172A;border:2px solid #fff;flex:none";
  el.appendChild(dot);
  if (showLabel) {
    const label = document.createElement("span");
    label.textContent = name;
    label.style.cssText =
      "background:#fff;border:1px solid #CBD5E1;border-radius:999px;padding:2px 8px;font-size:12px;font-weight:600;white-space:nowrap;box-shadow:0 1px 3px rgba(20,23,31,.15)";
    el.appendChild(label);
  }
  return el;
}

const areaPolygon = (a: NonNullable<DeliveryMapProps["area"]>): GeoJSON.Feature => ({
  type: "Feature",
  properties: {},
  geometry: {
    type: "Polygon",
    coordinates: [
      [
        [a.minLng, a.minLat],
        [a.maxLng, a.minLat],
        [a.maxLng, a.maxLat],
        [a.minLng, a.maxLat],
        [a.minLng, a.minLat],
      ],
    ],
  },
});

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

/** MapLibre + OpenFreeMap map shared by the home page, /ship/plans and the tracking page. Import through ./index (client only). */
export default function DeliveryMap({
  routes,
  stations,
  stationLabels = false,
  pickup,
  dropoff,
  area,
  device,
  fleet,
  legend,
  className,
}: DeliveryMapProps) {
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
          m.addSource("area", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
          m.addLayer({ id: "area-fill", type: "fill", source: "area", paint: { "fill-color": "#4F46E5", "fill-opacity": 0.06 } });
          m.addLayer({
            id: "area-line",
            type: "line",
            source: "area",
            paint: { "line-color": "#4F46E5", "line-width": 2, "line-dasharray": [2, 2] },
          });
          m.addSource("routes",{ type: "geojson", data: { type: "FeatureCollection", features: [] } });
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

  // Service area
  const areaKey = JSON.stringify(area ?? null);
  useEffect(() => {
    if (!ready || !mapRef.current) return;
    const a = JSON.parse(areaKey) as DeliveryMapProps["area"] | null;
    (mapRef.current.getSource("area") as maplibregl.GeoJSONSource).setData({
      type: "FeatureCollection",
      features: a ? [areaPolygon(a)] : [],
    });
  }, [ready, areaKey]);

  // Fixed markers
  const stationsKey = JSON.stringify(stations ?? []);
  const placesKey = JSON.stringify([pickup ?? null, dropoff ?? null]);
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) return;
    const list = JSON.parse(stationsKey) as NonNullable<DeliveryMapProps["stations"]>;
    const markers = list.map((s) =>
      new maplibregl.Marker({ element: stationPin(s.name, stationLabels), anchor: stationLabels ? "left" : "center" }).setLngLat([
        s.lng,
        s.lat,
      ]),
    );
    if (pickup) markers.push(new maplibregl.Marker({ element: pin("P", "#0F172A") }).setLngLat([pickup.lng, pickup.lat]));
    if (dropoff) markers.push(new maplibregl.Marker({ element: pin("D", "#4F46E5") }).setLngLat([dropoff.lng, dropoff.lat]));
    markers.forEach((m) => m.addTo(map));
    return () => markers.forEach((m) => m.remove());
    // eslint-disable-next-line react-hooks/exhaustive-deps -- placesKey stands in for pickup and dropoff
  }, [ready, stationsKey, placesKey, stationLabels]);

  // Frame everything once per set of places; later route changes must not move the user's view.
  const routesRef = useRef(routes);
  routesRef.current = routes;
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) return;
    const bounds = new maplibregl.LngLatBounds();
    const places = JSON.parse(placesKey) as ({ lat: number; lng: number } | null)[];
    places.forEach((p) => p && bounds.extend([p.lng, p.lat]));
    (JSON.parse(stationsKey) as { lat: number; lng: number }[]).forEach((s) => bounds.extend([s.lng, s.lat]));
    routesRef.current.forEach((r) => r.points.forEach((p) => bounds.extend(lngLat(p))));
    const a = JSON.parse(areaKey) as DeliveryMapProps["area"] | null;
    if (a) {
      bounds.extend([a.minLng, a.minLat]);
      bounds.extend([a.maxLng, a.maxLat]);
    }
    if (bounds.isEmpty()) return;
    // Extra room on the right keeps markers clear of the zoom buttons.
    map.fitBounds(bounds, { padding: { top: 40, bottom: 40, left: 40, right: 76 }, maxZoom: 15, duration: 0 });
  }, [ready, placesKey, stationsKey, areaKey]);

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

  // Fleet: one marker per device id, moved in place on every update.
  const fleetMarkers = useRef(new Map<string, maplibregl.Marker>());
  const fleetKey = JSON.stringify(fleet ?? []);
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) return;
    const list = JSON.parse(fleetKey) as NonNullable<DeliveryMapProps["fleet"]>;
    const seen = new Set<string>();
    for (const d of list) {
      seen.add(d.id);
      let m = fleetMarkers.current.get(d.id);
      if (!m) {
        const el = devicePin(d.deviceType);
        el.style.width = el.style.height = "28px";
        m = new maplibregl.Marker({ element: el }).setLngLat([d.lng, d.lat]).addTo(map);
        fleetMarkers.current.set(d.id, m);
      }
      m.setLngLat([d.lng, d.lat]);
      const el = m.getElement();
      el.style.opacity = d.state === "on_order" || d.state === "returning" ? "1" : "0.45";
      el.title = d.label ?? d.id;
    }
    fleetMarkers.current.forEach((m, id) => {
      if (!seen.has(id)) {
        m.remove();
        fleetMarkers.current.delete(id);
      }
    });
  }, [ready, fleetKey]);
  useEffect(() => {
    const markers = fleetMarkers.current;
    return () => {
      markers.forEach((m) => m.remove());
      markers.clear();
    };
  }, []);

  return (
    <div className={`relative overflow-hidden rounded-lg border border-slate-200 bg-slate-100 ${className ?? ""}`}>
      <div ref={box} style={{ position: "absolute", inset: 0 }} role="img" aria-label="Map of San Francisco with the delivery routes" />
      {legend && (
        <div className="absolute left-3.5 top-3.5 rounded-md border border-slate-300 bg-white px-3 py-2 text-xs leading-relaxed shadow-sm">
          <div className="mb-0.5 font-semibold">Legend</div>
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
            <span className="inline-block h-2.5 w-2.5 rounded-sm bg-slate-900" />
            Station
          </div>
        </div>
      )}
      <div className="absolute right-3.5 top-3.5 flex flex-col overflow-hidden rounded-md border border-slate-300 shadow-sm">
        <button
          type="button"
          aria-label="Zoom in"
          onClick={() => mapRef.current?.zoomIn()}
          className="h-11 w-11 bg-white text-xl text-slate-700 hover:bg-slate-100"
        >
          +
        </button>
        <button
          type="button"
          aria-label="Zoom out"
          onClick={() => mapRef.current?.zoomOut()}
          className="h-11 w-11 border-t border-slate-200 bg-white text-xl text-slate-700 hover:bg-slate-100"
        >
          −
        </button>
      </div>
    </div>
  );
}
