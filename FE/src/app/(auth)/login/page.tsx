"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { setToken } from "@/lib/auth";
import { GoogleSignInButton, GOOGLE_CLIENT_ID } from "@/components/account/GoogleSignInButton";
import type { User } from "@/types/contract";
import { Logo } from "@/components/ui/Logo";

interface AuthResponse {
  access_token: string;
  user: User;
}

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "Wrong email or password.",
  EMAIL_TAKEN: "An account with this email already exists. Sign in instead.",
  VALIDATION_ERROR: "Check the fields: a valid email and a password of at least 8 characters.",
  NETWORK_ERROR: "Could not reach the server.",
  ACCOUNT_DISABLED: "This account has been disabled. Contact support.",
  INVALID_GOOGLE_TOKEN: "Google sign-in could not be verified. Try again.",
  GOOGLE_ACCOUNT_MISMATCH: "This email is already linked to a different Google account.",
  GOOGLE_EMAIL_NOT_TRUSTED: "An account with this email already exists. Sign in with your password instead.",
  GOOGLE_NOT_CONFIGURED: "Google sign-in is not set up on the server yet.",
  GOOGLE_UNAVAILABLE: "Could not reach Google. Try again in a moment.",
};

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  // Only same-site paths, so ?next= cannot redirect off the app.
  const next = params.get("next");
  const target = next && next.startsWith("/") && !next.startsWith("//") ? next : "/ship";

  const finish = (res: AuthResponse) => {
    qc.clear();
    setToken(res.access_token);
    qc.setQueryData(["me"], res.user);
    router.replace(target);
  };

  const attempt = async (call: () => Promise<AuthResponse>) => {
    setError("");
    setBusy(true);
    try {
      finish(await call());
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      setError(MESSAGES[code] ?? "Something went wrong. Try again.");
    } finally {
      setBusy(false);
    }
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    void attempt(() =>
      mode === "login"
        ? api.post<AuthResponse>("/auth/login", { email, password })
        : api.post<AuthResponse>("/auth/register", { email, password, display_name: name }),
    );
  };

  const withGoogle = (idToken: string) => void attempt(() => api.post<AuthResponse>("/auth/google", { id_token: idToken }));

  const input = "w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-sky-500 focus:outline-none";

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div>
          <h1 className="text-xl font-semibold text-slate-900"><Logo size={28} /></h1>
          <p className="text-sm text-slate-500">{mode === "login" ? "Sign in to continue" : "Create your account"}</p>
        </div>

        {mode === "register" && (
          <label className="block space-y-1 text-sm">
            <span className="text-slate-700">Name</span>
            <input className={input} value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} />
          </label>
        )}
        <label className="block space-y-1 text-sm">
          <span className="text-slate-700">Email</span>
          <input className={input} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label className="block space-y-1 text-sm">
          <span className="text-slate-700">Password</span>
          <input
            className={input}
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            minLength={mode === "register" ? 8 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {mode === "register" && <span className="text-xs text-slate-400">At least 8 characters</span>}
        </label>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-md bg-sky-600 py-2 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50"
        >
          {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}
        </button>

        {GOOGLE_CLIENT_ID && (
          <div className="space-y-3">
            <div className="flex items-center gap-3 text-xs text-slate-400">
              <span className="h-px flex-1 bg-slate-200" />
              or
              <span className="h-px flex-1 bg-slate-200" />
            </div>
            <GoogleSignInButton onCredential={withGoogle} />
          </div>
        )}

        <p className="text-center text-sm text-slate-500">
          {mode === "login" ? "New to SkyLane? " : "Already have an account? "}
          <button
            type="button"
            className="font-medium text-sky-600 hover:underline"
            onClick={() => {
              setMode(mode === "login" ? "register" : "login");
              setError("");
            }}
          >
            {mode === "login" ? "Create an account" : "Sign in"}
          </button>
        </p>
      </form>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
