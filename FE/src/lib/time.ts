// All times come from the API as UTC ISO strings (D22) and are shown in US Pacific time.
const TZ = "America/Los_Angeles";

const timeFmt = new Intl.DateTimeFormat("en-US", { timeZone: TZ, hour: "numeric", minute: "2-digit" });
const dateFmt = new Intl.DateTimeFormat("en-US", { timeZone: TZ, month: "short", day: "numeric" });
const dateTimeFmt = new Intl.DateTimeFormat("en-US", {
  timeZone: TZ,
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
});

const toDate = (iso: string | Date) => (typeof iso === "string" ? new Date(iso) : iso);

export const formatTime = (iso: string | Date) => timeFmt.format(toDate(iso));
export const formatDate = (iso: string | Date) => dateFmt.format(toDate(iso));
export const formatDateTime = (iso: string | Date) => dateTimeFmt.format(toDate(iso));

/** 125 -> "2:05"; 3725 -> "1:02:05". Negative values clamp to 0. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

/** Seconds from now until `iso` (negative if in the past). */
export const secondsUntil = (iso: string, now = Date.now()) => (new Date(iso).getTime() - now) / 1000;

// Units (D21): the API uses cents and meters; the UI shows dollars and miles.
export const formatPrice = (cents: number) => `$${(cents / 100).toFixed(2)}`;
export const formatMiles = (meters: number) => `${(meters / 1609.344).toFixed(1)} mi`;

/** Orders are UUIDs; the UI shows the first 8 characters (D14). */
export const shortId = (id: string) => id.slice(0, 8).toUpperCase();
