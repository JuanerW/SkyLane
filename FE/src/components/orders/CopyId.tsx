"use client";

import { useState } from "react";
import { shortId } from "@/lib/time";

/** Shows the first 8 characters of the order UUID; click copies the full id (D14). */
export function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      title="Copy full order ID"
      onClick={(e) => {
        e.stopPropagation();
        void navigator.clipboard?.writeText(id).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1200);
        });
      }}
      className="font-mono text-sm text-slate-700 hover:text-slate-900"
    >
      #{shortId(id)}
      {copied && <span className="ml-1 font-sans text-xs text-emerald-600">Copied</span>}
    </button>
  );
}
