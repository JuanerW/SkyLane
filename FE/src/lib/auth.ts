// Token storage (D5): localStorage + Authorization: Bearer.
// The /login page (Yuning) calls setToken after register / login.
const KEY = "skylane.token";
const listeners = new Set<() => void>();

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string) {
  try {
    window.localStorage.setItem(KEY, token);
  } catch {
    /* storage unavailable */
  }
  listeners.forEach((l) => l());
}

export function clearToken() {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
  listeners.forEach((l) => l());
}

/** For useSyncExternalStore: notified on setToken / clearToken and on changes from other tabs. */
export function subscribeToken(listener: () => void) {
  listeners.add(listener);
  const onStorage = (e: StorageEvent) => e.key === KEY && listener();
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}
