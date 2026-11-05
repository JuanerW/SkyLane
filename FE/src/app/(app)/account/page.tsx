"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { setToken } from "@/lib/auth";
import { useAuth } from "@/hooks/useAuth";
import type { User } from "@/types/contract";

const MESSAGES: Record<string, string> = {
  INVALID_CURRENT_PASSWORD: "The current password is incorrect.",
  VALIDATION_ERROR: "Check the fields and try again.",
  NETWORK_ERROR: "Could not reach the server.",
};

const input = "w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-sky-500 focus:outline-none";
const primary = "rounded-md bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50";

type Notice = { kind: "ok" | "error"; text: string } | null;

function NoticeLine({ notice }: { notice: Notice }) {
  if (!notice) return null;
  return <p className={`text-sm ${notice.kind === "ok" ? "text-emerald-600" : "text-red-600"}`}>{notice.text}</p>;
}

function errorText(err: unknown): string {
  const code = err instanceof ApiError ? err.code : "";
  return MESSAGES[code] ?? "Something went wrong. Try again.";
}

function ProfileForm({ user }: { user: User }) {
  const qc = useQueryClient();
  const [name, setName] = useState(user.display_name);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const trimmed = name.trim();

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setNotice(null);
    try {
      const updated = await api.patch<User>("/me", { display_name: trimmed });
      qc.setQueryData(["me"], updated);
      setName(updated.display_name);
      setNotice({ kind: "ok", text: "Saved." });
    } catch (err) {
      setNotice({ kind: "error", text: errorText(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-3 rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900">Profile</h2>
      <div className="flex justify-between text-sm">
        <span className="text-slate-500">Email</span>
        <span className="text-slate-900">{user.email}</span>
      </div>
      <label className="block space-y-1 text-sm">
        <span className="text-slate-700">Display name</span>
        <input className={input} value={name} onChange={(e) => setName(e.target.value)} maxLength={80} required />
      </label>
      <NoticeLine notice={notice} />
      <button type="submit" className={primary} disabled={busy || !trimmed || trimmed === user.display_name}>
        {busy ? "Saving…" : "Save"}
      </button>
    </form>
  );
}

function PasswordForm({ user }: { user: User }) {
  const qc = useQueryClient();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setNotice(null);
    if (next !== confirm) {
      setNotice({ kind: "error", text: "The new passwords do not match." });
      return;
    }
    setBusy(true);
    try {
      // The server signs every other device out and hands back a fresh token so this session stays signed in.
      const res = await api.put<{ access_token: string; user: User }>("/me/password", {
        current_password: user.has_password ? current : undefined,
        new_password: next,
      });
      setToken(res.access_token);
      qc.setQueryData(["me"], res.user);
      setCurrent("");
      setNext("");
      setConfirm("");
      setNotice({ kind: "ok", text: user.has_password ? "Password changed. Other devices were signed out." : "Password set." });
    } catch (err) {
      setNotice({ kind: "error", text: errorText(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-3 rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900">{user.has_password ? "Change password" : "Set a password"}</h2>
      {!user.has_password && (
        <p className="text-xs text-slate-500">You signed up with Google. Set a password to also sign in with your email.</p>
      )}
      {user.has_password && (
        <label className="block space-y-1 text-sm">
          <span className="text-slate-700">Current password</span>
          <input className={input} type="password" autoComplete="current-password" value={current}
                 onChange={(e) => setCurrent(e.target.value)} required />
        </label>
      )}
      <label className="block space-y-1 text-sm">
        <span className="text-slate-700">New password</span>
        <input className={input} type="password" autoComplete="new-password" minLength={8} maxLength={100} value={next}
               onChange={(e) => setNext(e.target.value)} required />
        <span className="text-xs text-slate-400">At least 8 characters</span>
      </label>
      <label className="block space-y-1 text-sm">
        <span className="text-slate-700">Confirm new password</span>
        <input className={input} type="password" autoComplete="new-password" minLength={8} maxLength={100} value={confirm}
               onChange={(e) => setConfirm(e.target.value)} required />
      </label>
      <NoticeLine notice={notice} />
      <button type="submit" className={primary} disabled={busy}>
        {busy ? "Saving…" : user.has_password ? "Change password" : "Set password"}
      </button>
    </form>
  );
}

export default function AccountPage() {
  const { user, logout } = useAuth();

  return (
    <div className="mx-auto max-w-xl space-y-6 p-6">
      <h1 className="text-xl font-semibold text-slate-900">Account</h1>

      {!user ? (
        <p className="text-sm text-slate-400">Loading…</p>
      ) : (
        <>
          {/* key remounts the forms if a different user is loaded, so no stale field values carry over */}
          <ProfileForm key={`profile-${user.id}`} user={user} />

          <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm">
            <h2 className="mb-2 text-sm font-semibold text-slate-900">Sign-in methods</h2>
            <ul className="space-y-1 text-slate-700">
              <li className="flex justify-between">
                <span>Email and password</span>
                <span className={user.has_password ? "text-emerald-600" : "text-slate-400"}>
                  {user.has_password ? "On" : "Not set"}
                </span>
              </li>
              <li className="flex justify-between">
                <span>Google</span>
                <span className={user.google_linked ? "text-emerald-600" : "text-slate-400"}>
                  {user.google_linked ? "Linked" : "Not linked"}
                </span>
              </li>
            </ul>
            {!user.google_linked && (
              <p className="mt-2 text-xs text-slate-400">
                To link Google, sign out and choose “Continue with Google” with the same email address.
              </p>
            )}
          </div>

          <PasswordForm key={`password-${user.id}-${user.has_password}`} user={user} />
        </>
      )}

      <button onClick={logout} className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50">
        Sign out
      </button>
    </div>
  );
}
