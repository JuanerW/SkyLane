"use client";

import { useAuth } from "@/hooks/useAuth";

// Placeholder for round 2 (README: Account page, Google login, change password). Only shows who is signed in.
export default function AccountPage() {
  const { user, logout } = useAuth();

  return (
    <div className="mx-auto max-w-xl space-y-6 p-6">
      <h1 className="text-xl font-semibold text-slate-900">Account</h1>

      <div className="rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-800">
        <p className="font-medium">Coming in round 2</p>
        <p className="mt-1">
          This page is a placeholder. Still to build: edit display name (<code>PATCH /me</code>), change password
          (<code>PUT /me/password</code>) and Google sign-in (<code>POST /auth/google</code>). See docs/design.md.
        </p>
      </div>

      <dl className="space-y-2 rounded-lg border border-slate-200 bg-white p-4 text-sm">
        <div className="flex justify-between">
          <dt className="text-slate-500">Email</dt>
          <dd className="text-slate-900">{user?.email ?? "…"}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Name</dt>
          <dd className="text-slate-900">{user?.display_name ?? "…"}</dd>
        </div>
      </dl>

      <button
        onClick={logout}
        className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
      >
        Sign out
      </button>
    </div>
  );
}
