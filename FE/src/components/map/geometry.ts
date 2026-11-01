import type { LatLngTuple } from "@/types/contract";

/** Drones have no road route (D18): bend each straight leg into a shallow arc for display. */
export function arcPath(points: LatLngTuple[], steps = 32): LatLngTuple[] {
  const out: LatLngTuple[] = points.slice(0, 1);
  for (let i = 0; i < points.length - 1; i++) {
    const [aLat, aLng] = points[i];
    const [bLat, bLng] = points[i + 1];
    const ctrlLat = (aLat + bLat) / 2 + (bLng - aLng) * 0.18;
    const ctrlLng = (aLng + bLng) / 2 - (bLat - aLat) * 0.18;
    for (let s = 1; s <= steps; s++) {
      const t = s / steps;
      const u = 1 - t;
      out.push([u * u * aLat + 2 * u * t * ctrlLat + t * t * bLat, u * u * aLng + 2 * u * t * ctrlLng + t * t * bLng]);
    }
  }
  return out;
}

/** The point a fraction `t` (0–1) of the way along a path, by length. */
export function pointAlong(points: LatLngTuple[], t: number): LatLngTuple {
  const seg = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]));
  let left = Math.min(1, Math.max(0, t)) * seg.reduce((a, b) => a + b, 0);
  for (let i = 0; i < seg.length; i++) {
    if (left <= seg[i] && seg[i] > 0) {
      const f = left / seg[i];
      return [points[i][0] + (points[i + 1][0] - points[i][0]) * f, points[i][1] + (points[i + 1][1] - points[i][1]) * f];
    }
    left -= seg[i];
  }
  return points[points.length - 1];
}
