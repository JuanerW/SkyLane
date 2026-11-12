// Design 8.1: operator back office (/api/v1/ops, /api/v1/admin). Mirrors BE ops/OpsDtos.java.
import type { DeviceType, OrderStatus } from "./contract";

export type DeviceState = "idle" | "on_order" | "returning" | "maintenance" | "offline";

export interface LiveDevice {
  device_id: string;
  code: string;
  device_type: DeviceType;
  station_id: string;
  state: DeviceState;
  lat: number;
  lng: number;
  order_id: string | null;
}

export interface StationCount {
  station_id: string;
  name: string;
  idle: number;
  on_order: number;
  returning: number;
  inactive: number;
}

export interface OpsOverview {
  stations: StationCount[];
  orders_today: number;
  active_orders: number;
  scheduled_orders: number;
}

export interface OpsDevice {
  device_id: string;
  code: string;
  device_type: DeviceType;
  status: "active" | "maintenance" | "offline";
  state: DeviceState;
  station_id: string;
  station_name: string;
  operator_id: string | null;
  lat: number;
  lng: number;
  order_id: string | null;
  future_bookings: number;
}

export interface Booking {
  kind: "order" | "return" | "charging" | "maintenance";
  order_id: string | null;
  starts_at: string;
  ends_at: string;
  order_status: OrderStatus | null;
}

export interface OpsOrder {
  order_id: string;
  status: OrderStatus;
  device_code: string;
  device_type: DeviceType;
  station_name: string;
  recipient_name: string;
  recipient_phone_last4: string;
  pickup_text: string;
  dropoff_text: string;
  price_cents: number;
  created_at: string;
  departs_at: string;
  eta_at: string;
  completed_at: string | null;
  /** D44: past its ETA. */
  late: boolean;
}

export interface OpsMetrics {
  from: string;
  to: string;
  completed: number;
  on_time_rate: number | null;
  avg_delivery_minutes: number | null;
  ratings: Record<string, number>;
  utilization: number | null;
}

export interface OperatorRef {
  id: string;
  name: string;
}

export interface StaffUser {
  id: string;
  email: string;
  display_name: string;
  role: "customer" | "operator" | "admin";
  operator_id: string | null;
  disabled_at: string | null;
}
