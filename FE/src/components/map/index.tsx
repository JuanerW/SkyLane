"use client";

import dynamic from "next/dynamic";

export type { DeliveryMapProps, MapRoute } from "./DeliveryMap";
export { pointAlong, arcPath } from "./geometry";

// MapLibre needs the browser, so the map only renders on the client.
export const DeliveryMap = dynamic(() => import("./DeliveryMap"), {
  ssr: false,
  loading: () => <div className="h-full w-full animate-pulse rounded-lg bg-slate-100" />,
});
