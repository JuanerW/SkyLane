"use client";

import { useEffect, useRef, useState } from "react";

export const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

const SCRIPT_SRC = "https://accounts.google.com/gsi/client";

interface GoogleIdApi {
  initialize(config: { client_id: string; callback: (res: { credential: string }) => void }): void;
  renderButton(el: HTMLElement, options: Record<string, unknown>): void;
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } };
  }
}

let scriptPromise: Promise<void> | null = null;

/** Loads Google Identity Services once per page. */
function loadGoogleScript(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve();
  scriptPromise ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = SCRIPT_SRC;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      scriptPromise = null; // let a later render try again
      reject(new Error("Google script failed to load"));
    };
    document.head.appendChild(script);
  });
  return scriptPromise;
}

/**
 * The official "Sign in with Google" button. It hands back a Google ID token, which the parent sends to
 * POST /auth/google. Renders nothing when NEXT_PUBLIC_GOOGLE_CLIENT_ID is not set.
 */
export function GoogleSignInButton({ onCredential }: { onCredential: (idToken: string) => void }) {
  const holder = useRef<HTMLDivElement>(null);
  const latest = useRef(onCredential);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    latest.current = onCredential;
  }, [onCredential]);

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID) return;
    let cancelled = false;
    loadGoogleScript()
      .then(() => {
        const el = holder.current;
        if (cancelled || !el || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          callback: (res) => latest.current(res.credential),
        });
        // Google draws the button itself; its width must be between 200 and 400 px.
        const width = Math.min(400, Math.max(200, Math.floor(el.clientWidth)));
        window.google.accounts.id.renderButton(el, { theme: "outline", size: "large", text: "continue_with", width });
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, []);

  if (!GOOGLE_CLIENT_ID) return null;
  if (failed) {
    return <p className="text-center text-xs text-slate-400">Google sign-in could not be loaded. Use email instead.</p>;
  }
  return <div ref={holder} className="flex min-h-[44px] w-full justify-center" />;
}
