export type ActionStatus = "pending" | "confirmed" | "rejected" | "expired" | "failed";
export interface AgentAction {
  action_id: string; kind: "place_order" | "cancel_order"; status: ActionStatus; expires_at: string;
  payload: {
    quote_id?: string; option_id?: string; order_id?: string; reason?: string;
    recipient?: { name: string; phone: string };
    summary?: {
      station_name?: string; device_type?: "robot" | "drone"; price_cents?: number;
      eta_at?: string; departs_at?: string; pickup_text?: string; dropoff_text?: string;
      item_description?: string; weight_kg?: number; recipient_name?: string;
    };
  };
  result: { order_id?: string; status?: string; error?: { code: string; message: string } } | null;
}
export interface AgentMessage {
  id: number; role: "user" | "assistant" | "tool"; created_at: string;
  content: {
    kind: "message" | "turn"; turn_id: string; blocks?: { type: string; text?: string }[];
    status?: string; lease_until?: string; error_code?: string;
  };
}
export interface Conversation { conversation_id: string; messages: AgentMessage[]; actions: AgentAction[] }
export type AgentEvent =
  | { event: "delta"; data: { text: string } }
  | { event: "action"; data: AgentAction }
  | { event: "done"; data: { turn_id: string; success: boolean } }
  | { event: "error"; data: { error: { code: string; message: string } } };
export interface ActionDecision { action_id: string; kind: AgentAction["kind"]; status: ActionStatus; result: AgentAction["result"] }
