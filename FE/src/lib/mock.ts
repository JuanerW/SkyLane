// ============================================================================
//  PLACEHOLDER — FAKE BACKEND. NOT PART OF THE PRODUCT.
// ============================================================================
//  Exists only so the frontend can be built before the backend endpoints exist.
//  Active ONLY when NEXT_PUBLIC_USE_MOCK=1 (see .env.example). With it unset or 0,
//  lib/api.ts and lib/sse.ts call the real backend and nothing here runs.
//
//  - Data is in memory, resets on page reload, and is NOT the real seed data
//    (station and device codes are copied from BE DevRunner so they look familiar).
//  - Response shapes follow docs/design.md §4. If the real backend differs,
//    the backend DTO / OpenAPI wins — fix this file, not the pages.
//  - The first order is "live": it replays the simulator at about 10x speed so the
//    tracking page has something to show.
//  - Pages must never import this file. Only lib/api.ts and lib/sse.ts do.
//
//  TODO(round 1 integration): delete this file and the USE_MOCK branches once
//  every endpoint used by the FE is implemented.
// ============================================================================
import type { Method } from "./api";
import type { StreamOptions } from "./sse";
import type {
  LatLngTuple,
  OrderDetail,
  OrderStatus,
  OrderSummary,
  PositionEvent,
  QuoteOption,
  QuoteRequest,
  QuoteResponse,
  Station,
  TimelineEntry,
  User,
} from "@/types/contract";
import { TERMINAL_STATUSES } from "@/types/contract";

export const MOCK_TOKEN = "mock-token";

const STATIONS: Station[] = [
  { id: "st-soma", code: "soma", name: "SoMa Station", lat: 37.7785, lng: -122.395 },
  { id: "st-mission", code: "mission", name: "Mission Station", lat: 37.7599, lng: -122.4148 },
  { id: "st-marina", code: "marina", name: "Marina Station", lat: 37.8008, lng: -122.4368 },
];

let mockPassword = "password123"; // the seed account's password; PUT /me/password replaces it

const USER: User = {
  id: "00000000-0000-4000-8000-000000000001",
  email: "alice@skylane.dev",
  display_name: "Alice",
  has_password: true,
  google_linked: false,
  created_at: "2026-10-01T17:00:00Z",
};

// Live-order schedule, in seconds after the order is placed (≈10x the real simulator).
const LIVE_PLAN: [OrderStatus, number][] = [
  ["dispatched", 15],
  ["picked_up", 35],
  ["in_transit", 45],
  ["arrived", 85],
  ["completed", 95],
];

interface MockOrder {
  detail: OrderDetail;
  /** Set for orders that progress in real time. */
  live?: { placedAt: number };
}

class Ids {
  private n = 1;
  next() {
    return this.n++;
  }
}
const eventIds = new Ids();

const iso = (ms: number) => new Date(ms).toISOString();
const MIN = 60_000;

function line(from: LatLngTuple, to: LatLngTuple, steps = 12): LatLngTuple[] {
  return Array.from({ length: steps + 1 }, (_, i) => [
    from[0] + ((to[0] - from[0]) * i) / steps,
    from[1] + ((to[1] - from[1]) * i) / steps,
  ]);
}

function buildOrder(opts: {
  id: string;
  station: Station;
  device: string;
  type: "robot" | "drone";
  createdAt: number;
  pickup: { text: string; lat: number; lng: number };
  dropoff: { text: string; lat: number; lng: number };
  item: string;
  weight: number;
  recipient: string;
  price: number;
  distance: number;
  status: OrderStatus;
  review?: { rating: number; comment: string };
}): OrderDetail {
  const s: LatLngTuple = [opts.station.lat, opts.station.lng];
  const p: LatLngTuple = [opts.pickup.lat, opts.pickup.lng];
  const d: LatLngTuple = [opts.dropoff.lat, opts.dropoff.lng];
  const route = [...line(s, p), ...line(p, d).slice(1)];
  const departs = opts.createdAt + MIN;
  const eta = opts.createdAt + 25 * MIN;
  const flow: OrderStatus[] = ["confirmed", "dispatched", "picked_up", "in_transit", "arrived", "completed"];
  const reached = opts.status === "canceled" ? ["confirmed", "canceled"] : flow.slice(0, flow.indexOf(opts.status) + 1);
  const offsets: Record<string, number> = {
    confirmed: 0,
    dispatched: 1,
    picked_up: 8,
    in_transit: 9,
    arrived: 24,
    completed: 25,
    canceled: 0.5,
  };
  const timeline: TimelineEntry[] = (reached as OrderStatus[]).map((st) => ({
    event_id: eventIds.next(),
    status: st,
    occurred_at: iso(opts.createdAt + offsets[st] * MIN),
  }));
  return {
    order_id: opts.id,
    status: opts.status,
    device_type: opts.type,
    price_cents: opts.price,
    created_at: iso(opts.createdAt),
    departs_at: iso(departs),
    eta_at: iso(eta),
    completed_at: opts.status === "completed" ? iso(eta) : null,
    pickup_text: opts.pickup.text,
    dropoff_text: opts.dropoff.text,
    recipient_name: opts.recipient,
    reviewed: !!opts.review,
    device: { code: opts.device, device_type: opts.type },
    station: { code: opts.station.code, name: opts.station.name, lat: opts.station.lat, lng: opts.station.lng },
    item: { description: opts.item, is_fragile: false, weight_kg: opts.weight },
    recipient_phone: "+1 415 555 0100",
    notes: null,
    pickup: opts.pickup,
    dropoff: opts.dropoff,
    distance_m: opts.distance,
    route_points: route,
    cancellable: false,
    canceled_at: opts.status === "canceled" ? iso(opts.createdAt + 0.5 * MIN) : null,
    cancel_reason: opts.status === "canceled" ? "Changed my mind" : null,
    timeline,
    review: opts.review ? { ...opts.review, created_at: iso(eta + 10 * MIN) } : null,
  };
}

const FERRY = { text: "1 Ferry Building, San Francisco", lat: 37.7955, lng: -122.3937 };
const DOLORES = { text: "Dolores Park, San Francisco", lat: 37.7596, lng: -122.4269 };
const CHESTNUT = { text: "2100 Chestnut St, San Francisco", lat: 37.8003, lng: -122.4384 };
const ORACLE = { text: "24 Willie Mays Plaza, San Francisco", lat: 37.7786, lng: -122.3893 };

let orders: MockOrder[] | null = null;

function store(): MockOrder[] {
  if (orders) return orders;
  const now = Date.now();
  const day = 24 * 60 * MIN;
  const placedAt = now;
  const live = buildOrder({
    id: "7c1e2a9b-4f3d-4a6e-9b21-5d8f0c3e1a77",
    station: STATIONS[0],
    device: "DRN-101",
    type: "drone",
    createdAt: placedAt,
    pickup: ORACLE,
    dropoff: FERRY,
    item: "Birthday cake",
    weight: 1.5,
    recipient: "Carol Chen",
    price: 186,
    distance: 1890,
    status: "confirmed",
  });
  // Live order: drone flies straight from pickup to dropoff after leaving the station.
  live.departs_at = iso(placedAt + LIVE_PLAN[0][1] * 1000);
  live.eta_at = iso(placedAt + LIVE_PLAN[3][1] * 1000);
  live.cancellable = true;
  live.route_points = [
    [STATIONS[0].lat, STATIONS[0].lng],
    [ORACLE.lat, ORACLE.lng],
    [FERRY.lat, FERRY.lng],
  ];

  orders = [
    { detail: live, live: { placedAt } },
    {
      detail: buildOrder({
        id: "3a9d5c12-8e47-4b0f-a6c3-2f1e9d7b4c58",
        station: STATIONS[1],
        device: "R-202",
        type: "robot",
        createdAt: now - 2 * day,
        pickup: DOLORES,
        dropoff: ORACLE,
        item: "Books",
        weight: 3,
        recipient: "Dan Lee",
        price: 1080,
        distance: 5790,
        status: "completed",
        review: { rating: 5, comment: "Arrived early, great!" },
      }),
    },
    {
      detail: buildOrder({
        id: "b84f0e6d-1c29-47a5-8d3e-6a0b7f9c2e14",
        station: STATIONS[2],
        device: "DRN-301",
        type: "drone",
        createdAt: now - 4 * day,
        pickup: CHESTNUT,
        dropoff: FERRY,
        item: "Keys",
        weight: 0.2,
        recipient: "Erin Park",
        price: 100,
        distance: 3950,
        status: "completed",
      }),
    },
    {
      detail: buildOrder({
        id: "e2c7a1f4-5b8d-4e93-b0a6-9f3c1d5e7a20",
        station: STATIONS[0],
        device: "R-103",
        type: "robot",
        createdAt: now - 6 * day,
        pickup: ORACLE,
        dropoff: DOLORES,
        item: "Lunch box",
        weight: 1,
        recipient: "Frank Wu",
        price: 360,
        distance: 5790,
        status: "completed",
      }),
    },
    {
      detail: buildOrder({
        id: "51d3b9e8-7a26-4c4f-9e1b-0c8a6f2d3b95",
        station: STATIONS[1],
        device: "R-201",
        type: "robot",
        createdAt: now - 7 * day,
        pickup: DOLORES,
        dropoff: CHESTNUT,
        item: "Documents",
        weight: 0.5,
        recipient: "Grace Kim",
        price: 260,
        distance: 6400,
        status: "canceled",
      }),
    },
  ];
  return orders;
}

/** Advance live orders to wherever the clock says they should be. */
function tick(o: MockOrder) {
  if (!o.live || TERMINAL_STATUSES.includes(o.detail.status)) return;
  const elapsed = (Date.now() - o.live.placedAt) / 1000;
  const done = new Set(o.detail.timeline.map((t) => t.status));
  for (const [status, at] of LIVE_PLAN) {
    if (elapsed < at || done.has(status)) continue;
    o.detail.timeline.push({ event_id: eventIds.next(), status, occurred_at: iso(o.live.placedAt + at * 1000) });
    o.detail.status = status;
    o.detail.cancellable = false;
    if (status === "completed") o.detail.completed_at = iso(o.live.placedAt + at * 1000);
  }
}

function position(o: MockOrder): PositionEvent | null {
  const d = o.detail;
  if (!o.live || !["dispatched", "picked_up", "in_transit"].includes(d.status)) return null;
  const start = new Date(d.departs_at).getTime();
  const end = new Date(d.eta_at).getTime();
  const progress = Math.min(1, Math.max(0, (Date.now() - start) / (end - start)));
  const pts = d.route_points;
  const f = progress * (pts.length - 1);
  const i = Math.min(pts.length - 2, Math.floor(f));
  const t = f - i;
  return {
    lat: pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t,
    lng: pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t,
    progress,
    remaining_s: Math.max(0, (end - Date.now()) / 1000),
  };
}

function summary(d: OrderDetail): OrderSummary {
  const {
    order_id,
    status,
    device_type,
    price_cents,
    created_at,
    departs_at,
    eta_at,
    completed_at,
    pickup_text,
    dropoff_text,
    recipient_name,
    reviewed,
  } = d;
  return {
    order_id,
    status,
    device_type,
    price_cents,
    created_at,
    departs_at,
    eta_at,
    completed_at,
    pickup_text,
    dropoff_text,
    recipient_name,
    reviewed,
  };
}

class MockError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(code);
  }
}

function find(id: string): MockOrder {
  const o = store().find((x) => x.detail.order_id === id);
  if (!o) throw new MockError(404, "ORDER_NOT_FOUND");
  tick(o);
  return o;
}

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v));

export async function mockRequest<T>(method: Method, path: string, body?: unknown): Promise<T> {
  await new Promise((r) => setTimeout(r, 200));
  // Import lazily to avoid a circular import at module load.
  const { ApiError } = await import("./api");
  try {
    return clone(route(method, path.split("?")[0], body)) as T;
  } catch (e) {
    if (e instanceof MockError) throw new ApiError(e.status, e.code, e.code);
    throw e;
  }
}

function route(method: Method, path: string, body: unknown): unknown {
  const b = (body ?? {}) as Record<string, unknown>;
  let m: RegExpMatchArray | null;

  if (method === "GET" && path === "/me") return USER;
  if (method === "PATCH" && path === "/me") {
    const name = String(b.display_name ?? "").trim();
    if (!name || name.length > 80) throw new MockError(400, "VALIDATION_ERROR");
    USER.display_name = name;
    return USER;
  }
  if (method === "PUT" && path === "/me/password") {
    if (String(b.new_password ?? "").length < 8) throw new MockError(400, "VALIDATION_ERROR");
    // a Google-only account (has_password false) may set its first password without a current one
    if (USER.has_password && b.current_password !== mockPassword) throw new MockError(400, "INVALID_CURRENT_PASSWORD");
    mockPassword = String(b.new_password);
    USER.has_password = true;
    return { access_token: "mock-token", user: USER };
  }
  if (method === "GET" && path === "/stations") return STATIONS;
  if (method === "GET" && path === "/orders") {
    store().forEach(tick);
    return store()
      .map((o) => summary(o.detail))
      .sort((a, b2) => b2.created_at.localeCompare(a.created_at));
  }
  if (method === "GET" && (m = path.match(/^\/orders\/([^/]+)$/))) return find(m[1]).detail;
  if (method === "POST" && (m = path.match(/^\/orders\/([^/]+)\/cancel$/))) {
    const o = find(m[1]);
    if (o.detail.status !== "confirmed" || Date.now() >= new Date(o.detail.departs_at).getTime())
      throw new MockError(409, "NOT_CANCELLABLE");
    o.detail.status = "canceled";
    o.detail.cancellable = false;
    o.detail.canceled_at = iso(Date.now());
    o.detail.cancel_reason = (b.reason as string) ?? null;
    o.detail.timeline.push({ event_id: eventIds.next(), status: "canceled", occurred_at: o.detail.canceled_at });
    return { status: "canceled" };
  }
  if (method === "POST" && (m = path.match(/^\/orders\/([^/]+)\/review$/))) {
    const o = find(m[1]);
    if (o.detail.status !== "completed") throw new MockError(409, "NOT_COMPLETED");
    if (o.detail.review) throw new MockError(409, "ALREADY_REVIEWED");
    const rating = Number(b.rating);
    if (!Number.isInteger(rating) || rating < 1 || rating > 5) throw new MockError(400, "VALIDATION_ERROR");
    o.detail.review = { rating, comment: (b.comment as string) || null, created_at: iso(Date.now()) };
    o.detail.reviewed = true;
    return { rating, comment: o.detail.review.comment };
  }
  if (method === "POST" && path === "/quotes") return createQuote(body as QuoteRequest);
  const recommendationPath = path.match(/^\/quotes\/([^/]+)\/recommendation$/);
  if (method === "POST" && recommendationPath) {
    const quote = quotes.get(recommendationPath[1]);
    if (!quote) throw new MockError(404, "QUOTE_NOT_FOUND");
    if (Date.parse(quote.res.expires_at) <= Date.now()) throw new MockError(409, "QUOTE_EXPIRED");
    const option = quote.res.options.find(o => o.available && o.tags.includes("recommended")) ?? quote.res.options.find(o => o.available);
    if (!option) throw new MockError(409, "NO_AVAILABLE_OPTIONS");
    return { option_id: option.option_id, reason: "Demo recommendation balancing price and arrival time.", factors: { algorithm: "mock", preference: "balanced", weather_status: "unavailable" } };
  }
  if (method === "POST" && path === "/orders") {
    return createOrder(b.quote_id as string, b.option_id as string, b.recipient as { name: string; phone: string });
  }
  throw new MockError(404, "NOT_FOUND");
}

const quotes = new Map<string, { req: QuoteRequest; res: QuoteResponse }>();

function meters(a: LatLngTuple, b: LatLngTuple) {
  const dLat = (b[0] - a[0]) * 111_320;
  const dLng = (b[1] - a[1]) * 111_320 * Math.cos((a[0] * Math.PI) / 180);
  return Math.hypot(dLat, dLng);
}

const pathMeters = (pts: LatLngTuple[]) => pts.slice(1).reduce((sum, p, i) => sum + meters(pts[i], p), 0);

function createQuote(req: QuoteRequest): QuoteResponse {
  const inSf = (p: { lat: number; lng: number }) => p.lat >= 37.7 && p.lat <= 37.84 && p.lng >= -122.52 && p.lng <= -122.35;
  if (!inSf(req.pickup) || !inSf(req.dropoff)) throw new MockError(400, "OUT_OF_SERVICE_AREA");
  if (req.pickup.lat === req.dropoff.lat && req.pickup.lng === req.dropoff.lng) throw new MockError(400, "SAME_ADDRESS");

  const now = Date.now();
  const p: LatLngTuple = [req.pickup.lat, req.pickup.lng];
  const d: LatLngTuple = [req.dropoff.lat, req.dropoff.lng];
  // Design 8.4: a booked pickup moves every departure to that time.
  const start = req.pickup_after ? Math.max(now, new Date(req.pickup_after).getTime()) : now;
  const options: QuoteOption[] = STATIONS.flatMap((st, i) =>
    (["robot", "drone"] as const).map((type) => {
      const s: LatLngTuple = [st.lat, st.lng];
      // Robots turn at corners so the fake route looks like streets; drones fly straight.
      const route: LatLngTuple[] = type === "robot" ? [s, [s[0], p[1]], p, [p[0], d[1]], d] : [s, p, d];
      const distance = Math.round(pathMeters(route));
      const base = { option_id: crypto.randomUUID(), station: st, device_type: type, tags: [] as QuoteOption["tags"] };
      const out = { ...base, departs_at: null, eta_at: null, price_cents: null, distance_m: null, route_points: null };
      const reason =
        type === "drone" && req.weight_kg > 2
          ? "OVERWEIGHT"
          : type === "drone" && distance > 7500
            ? "OUT_OF_RANGE"
            : null;
      if (reason) return { ...out, available: false, unavailable_reason: reason };

      // The second station's drone is busy, so at least one card shows "Next free".
      const departs = start + (type === "drone" && i === 1 ? 12 * MIN : 0);
      const eta = departs + (distance / (type === "drone" ? 15 : 2) + (type === "drone" ? 120 : 300)) * 1000;
      if (req.arrive_within_minutes && eta > start + req.arrive_within_minutes * MIN) {
        return { ...out, available: false, unavailable_reason: "DEADLINE_MISSED" };
      }
      const miles = distance / 1609.344;
      return {
        ...base,
        available: true,
        unavailable_reason: null,
        departs_at: iso(departs),
        eta_at: iso(eta),
        price_cents: Math.max(100, Math.round(req.weight_kg * miles * 100)),
        distance_m: distance,
        route_points: route,
      };
    }),
  );

  const open = options.filter((o) => o.available);
  const time = (o: QuoteOption) => new Date(o.eta_at!).getTime();
  const best = (cmp: (a: QuoteOption, b: QuoteOption) => number) => [...open].sort(cmp)[0];
  if (open.length) {
    best((a, b2) => time(a) - time(b2)).tags.push("fastest");
    best((a, b2) => a.price_cents! - b2.price_cents!).tags.push("cheapest");
    best((a, b2) => time(a) + a.price_cents! * 1000 - time(b2) - b2.price_cents! * 1000).tags.push("recommended");
  }
  const res = { quote_id: crypto.randomUUID(), expires_at: iso(now + 10 * MIN), options };
  quotes.set(res.quote_id, { req, res });
  return res;
}

function createOrder(quoteId: string, optionId: string, recipient: { name: string; phone: string }) {
  const q = quotes.get(quoteId);
  if (!q || Date.now() > new Date(q.res.expires_at).getTime()) throw new MockError(409, "QUOTE_EXPIRED");
  const opt = q.res.options.find((o) => o.option_id === optionId);
  if (!opt || !opt.available) throw new MockError(404, "OPTION_NOT_FOUND");

  const placedAt = Date.now();
  const station = STATIONS.find((s) => s.id === opt.station.id)!;
  const detail = buildOrder({
    id: crypto.randomUUID(),
    station,
    device: opt.device_type === "drone" ? "DRN-101" : "R-101",
    type: opt.device_type,
    createdAt: placedAt,
    pickup: q.req.pickup,
    dropoff: q.req.dropoff,
    item: q.req.item.description,
    weight: q.req.weight_kg,
    recipient: recipient.name,
    price: opt.price_cents!,
    distance: opt.distance_m!,
    status: "confirmed",
  });
  // Same 10x replay as the first order, along the quoted route.
  detail.departs_at = iso(placedAt + LIVE_PLAN[0][1] * 1000);
  detail.eta_at = iso(placedAt + LIVE_PLAN[3][1] * 1000);
  detail.cancellable = true;
  detail.route_points = opt.route_points!;
  detail.recipient_phone = recipient.phone;
  detail.notes = q.req.notes ?? null;
  store().unshift({ detail, live: { placedAt } });
  return {
    order_id: detail.order_id,
    status: detail.status,
    device: detail.device,
    price_cents: detail.price_cents,
    departs_at: detail.departs_at,
    eta_at: detail.eta_at,
  };
}

/** Fake SSE for /orders/{id}/track: replays missed status events, then pushes position every second. */
export function mockStream(path: string, opts: StreamOptions): () => void {
  const id = path.match(/^\/orders\/([^/]+)\/track$/)?.[1];
  let sent = 0;
  let closed = false;
  opts.onState?.("open");

  const step = () => {
    if (closed || !id) return;
    let o: MockOrder;
    try {
      o = find(id);
    } catch {
      closed = true;
      opts.onState?.("failed");
      return;
    }
    for (const t of o.detail.timeline.slice(sent)) {
      const msg = {
        event: "status",
        id: String(t.event_id),
        data: JSON.stringify({ status: t.status, at: t.occurred_at }),
      };
      opts.onMessage(msg);
      if (opts.isTerminal?.(msg)) {
        closed = true;
        opts.onState?.("closed");
        return;
      }
    }
    sent = o.detail.timeline.length;
    const pos = position(o);
    if (pos) opts.onMessage({ event: "position", data: JSON.stringify(pos) });
  };

  step();
  const timer = setInterval(step, 1000);
  return () => {
    closed = true;
    clearInterval(timer);
  };
}
