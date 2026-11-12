"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";
import { ErrorState, LoadingBlock } from "@/components/ui/States";
import type { OperatorRef, StaffUser } from "@/types/ops";

// Design 8.1 (D33): admin creates operator companies and their accounts; no self sign-up for operators.
const errorText = (e: unknown) => (e instanceof ApiError ? e.message : "Something went wrong. Try again.");

export default function OpsAdminPage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const operators = useQuery({ queryKey: ["admin", "operators"], queryFn: () => api.get<OperatorRef[]>("/admin/operators") });
  const users = useQuery({ queryKey: ["admin", "users"], queryFn: () => api.get<StaffUser[]>("/admin/users") });

  const [company, setCompany] = useState("");
  const addCompany = useMutation({
    mutationFn: () => api.post<OperatorRef>("/admin/operators", { name: company }),
    onSuccess: () => {
      setCompany("");
      qc.invalidateQueries({ queryKey: ["admin", "operators"] });
    },
  });

  const [form, setForm] = useState({ email: "", display_name: "", password: "", operator_id: "" });
  const addUser = useMutation({
    mutationFn: () => api.post<StaffUser>("/admin/users", form),
    onSuccess: () => {
      setForm({ email: "", display_name: "", password: "", operator_id: "" });
      qc.invalidateQueries({ queryKey: ["admin", "users"] });
    },
  });

  const patch = useMutation({
    mutationFn: ({ id, body }: { id: string; body: { disabled?: boolean; force_logout?: boolean } }) =>
      api.patch<StaffUser>(`/admin/users/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "users"] }),
  });

  if (user?.role !== "admin") return <ErrorState message="Admins only." />;
  const opName = (id: string | null) => operators.data?.find((o) => o.id === id)?.name ?? "—";

  return (
    <div className="grid gap-5 lg:grid-cols-2">
      <section className="card px-5 py-4">
        <h2 className="mb-3 text-base font-semibold">Operator companies</h2>
        <form
          className="mb-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            addCompany.mutate();
          }}
        >
          <input className="inp" required maxLength={100} placeholder="Company name" aria-label="Company name" value={company} onChange={(e) => setCompany(e.target.value)} />
          <button type="submit" className="btn !h-11 !text-sm" disabled={addCompany.isPending}>
            Create
          </button>
        </form>
        {addCompany.isError && <div className="err mb-2">{errorText(addCompany.error)}</div>}
        {operators.isPending ? (
          <LoadingBlock label="Loading companies" rows={2} />
        ) : (
          <ul className="divide-y divide-slate-100 text-sm">
            {operators.data?.map((o) => (
              <li key={o.id} className="py-2">
                {o.name}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card px-5 py-4">
        <h2 className="mb-3 text-base font-semibold">New operator account</h2>
        <form
          className="grid gap-2 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            addUser.mutate();
          }}
        >
          <input className="inp" type="email" required placeholder="Email" aria-label="Email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <input className="inp" required placeholder="Name" aria-label="Name" value={form.display_name} onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
          <input className="inp" type="password" required minLength={8} placeholder="Initial password (8+)" aria-label="Initial password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
          <select className="inp" required aria-label="Company" value={form.operator_id} onChange={(e) => setForm({ ...form, operator_id: e.target.value })}>
            <option value="">Company…</option>
            {operators.data?.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
          <button type="submit" className="btn !h-11 !text-sm sm:col-span-2" disabled={addUser.isPending}>
            Create account
          </button>
        </form>
        {addUser.isError && <div className="err mt-2">{errorText(addUser.error)}</div>}
      </section>

      <section className="card overflow-x-auto lg:col-span-2">
        <h2 className="px-5 pb-2 pt-4 text-base font-semibold">Staff accounts</h2>
        {users.isPending && (
          <div className="px-5 pb-4">
            <LoadingBlock label="Loading accounts" rows={3} />
          </div>
        )}
        {users.isError && <ErrorState message="Could not load accounts." onRetry={() => users.refetch()} />}
        {patch.isError && <div className="err px-5">{errorText(patch.error)}</div>}
        {!!users.data?.length && (
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-5 py-2">Account</th>
                <th className="px-3 py-2">Role</th>
                <th className="px-3 py-2">Company</th>
                <th className="px-5 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.data.map((u) => (
                <tr key={u.id} className="border-t border-slate-100">
                  <td className="px-5 py-2.5">
                    <div className="font-semibold">{u.display_name}</div>
                    <div className="text-xs text-slate-500">
                      {u.email}
                      {u.disabled_at && <span className="ml-2 font-semibold text-rose-700">Disabled</span>}
                    </div>
                  </td>
                  <td className="px-3 py-2.5 capitalize">{u.role}</td>
                  <td className="px-3 py-2.5">{opName(u.operator_id)}</td>
                  <td className="px-5 py-2.5 text-right">
                    {u.id !== user.id && (
                      <div className="flex justify-end gap-2">
                        <button type="button" className="ghost !h-9" disabled={patch.isPending} onClick={() => patch.mutate({ id: u.id, body: { force_logout: true } })}>
                          Sign out
                        </button>
                        <button type="button" className="ghost !h-9" disabled={patch.isPending} onClick={() => patch.mutate({ id: u.id, body: { disabled: !u.disabled_at } })}>
                          {u.disabled_at ? "Enable" : "Disable"}
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
