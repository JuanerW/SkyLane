// Hand-written from docs/design.md §4 while the backend has no OpenAPI spec yet.
// Once springdoc is up, run `npm run gen:types` (writes src/types/api.ts) and re-export the
// generated schemas from here, so pages keep importing from "@/types/contract".

export type OrderStatus =
  | "confirmed"
  | "dispatched"
  | "picked_up"
  | "in_transit"
  | "arrived"
  | "completed"
  | "canceled"
  | "failed";

export type DeviceType = "robot" | "drone";

/** [lat, lng] */
export type LatLngTuple = [number, number];

export interface User {
  id: string;
  email: string;
  display_name: string;
  has_password: boolean;
  google_linked: boolean;
  created_at: string;
  /** Added in round 2 (design 8.3). Absent from older responses and mock data, so treat undefined as customer. */
  role?: "customer" | "operator" | "admin";
  operator_id?: string | null;
}

export interface Station {
  id: string;
  code: string;
  name: string;
  lat: number;
  lng: number;
}

/** GET /orders?limit=100 row */
export interface OrderSummary {
  order_id: string;
  status: OrderStatus;
  device_type: DeviceType;
  price_cents: number;
  created_at: string;
  departs_at: string;
  eta_at: string;
  completed_at: string | null;
  pickup_text: string;
  dropoff_text: string;
  recipient_name: string;
  reviewed: boolean;
}

export interface TimelineEntry {
  event_id: number;
  status: OrderStatus;
  occurred_at: string;
}

export interface Review {
  rating: number;
  comment: string | null;
  created_at?: string;
}

/** GET /orders/{id} */
export interface OrderDetail extends OrderSummary {
  device: { code: string; device_type: DeviceType } | null;
  station: { code: string; name: string; lat: number; lng: number } | null;
  item: { description: string; is_fragile: boolean; weight_kg: number };
  recipient_phone: string;
  notes: string | null;
  pickup: { text: string; lat: number; lng: number };
  dropoff: { text: string; lat: number; lng: number };
  distance_m: number;
  route_points: LatLngTuple[];
  cancellable: boolean;
  canceled_at: string | null;
  cancel_reason: string | null;
  timeline: TimelineEntry[];
  review: Review | null;
}

/** SSE `status` event payload (sent with an id) */
export interface StatusEvent {
  status: OrderStatus;
  at: string;
}

/** SSE `position` event payload */
export interface PositionEvent {
  lat: number;
  lng: number;
  progress: number;
  remaining_s: number;
}

export interface Place {
  text: string;
  lat: number;
  lng: number;
}

/** POST /quotes body */
export interface QuoteRequest {
  item: { description: string; fragile: boolean };
  weight_kg: number;
  pickup: Place;
  dropoff: Place;
  notes?: string;
  arrive_within_minutes?: number;
}

export type QuoteTag = "fastest" | "cheapest" | "recommended";

/** Time, price, distance and route are null when the option is unavailable. */
export interface QuoteOption {
  option_id: string;
  station: { id: string; name: string; lat: number; lng: number };
  device_type: DeviceType;
  available: boolean;
  unavailable_reason: string | null;
  departs_at: string | null;
  eta_at: string | null;
  price_cents: number | null;
  distance_m: number | null;
  route_points: LatLngTuple[] | null;
  tags: QuoteTag[];
}

export interface QuoteResponse {
  quote_id: string;
  expires_at: string;
  options: QuoteOption[];
  recommended_option_id?: string | null;
}

export interface Recommendation {
  option_id: string;
  reason: string;
  factors: {
    algorithm: string;
    preference: string;
    weather_status: string;
    wind_speed_mps?: number;
    [key: string]: unknown;
  };
}

/** POST /orders response */
export interface CreateOrderResponse {
  order_id: string;
  status: OrderStatus;
  device: { code: string; device_type: DeviceType } | null;
  price_cents: number;
  departs_at: string;
  eta_at: string;
}

export const TERMINAL_STATUSES: OrderStatus[] =["completed", "canceled", "failed"];

export const ACTIVE_FLOW: OrderStatus[] = ["confirmed", "dispatched", "picked_up", "in_transit", "arrived", "completed"];
