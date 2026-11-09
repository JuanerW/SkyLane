// Address autocomplete (Photon, public service, no key). Same San Francisco bounds as the backend (D23).
import type { Place } from "@/types/contract";
import { SERVICE_AREA } from "./serviceArea";

const PHOTON_URL = "https://photon.komoot.io/api/";

export const MIN_QUERY_LENGTH = 3;

interface PhotonFeature {
  // GeoJSON order: [lng, lat]
  geometry: { coordinates: [number, number] };
  properties: {
    name?: string;
    housenumber?: string;
    street?: string;
    district?: string;
    city?: string;
    state?: string;
    postcode?: string;
  };
}

function label(p: PhotonFeature["properties"]): string {
  const road = [p.housenumber, p.street].filter(Boolean).join(" ");
  const first = p.name && p.name !== road ? [p.name, road].filter(Boolean).join(", ") : road;
  return [first, p.city ?? "San Francisco", p.postcode].filter(Boolean).join(", ");
}

export async function searchAddress(query: string, signal?: AbortSignal): Promise<Place[]> {
  const { minLat, maxLat, minLng, maxLng } = SERVICE_AREA;
  const params = new URLSearchParams({
    q: query,
    limit: "6",
    lang: "en",
    bbox: `${minLng},${minLat},${maxLng},${maxLat}`,
  });
  const res = await fetch(`${PHOTON_URL}?${params}`, { signal });
  if (!res.ok) throw new Error(`Photon ${res.status}`);
  const data = (await res.json()) as { features: PhotonFeature[] };

  const seen = new Set<string>();
  return data.features
    .map((f) => ({ text: label(f.properties), lat: f.geometry.coordinates[1], lng: f.geometry.coordinates[0] }))
    .filter((p) => {
      const inside = p.lat >= minLat && p.lat <= maxLat && p.lng >= minLng && p.lng <= maxLng;
      if (!inside || !p.text || seen.has(p.text)) return false;
      seen.add(p.text);
      return true;
    });
}
