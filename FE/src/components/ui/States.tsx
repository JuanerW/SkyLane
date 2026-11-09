// Shared loading, empty and error states, so every page reads the same way.

export function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden="true" className={`animate-pulse rounded-lg bg-slate-100 ${className}`} />;
}

/** A few grey blocks while a list or page loads. */
export function LoadingBlock({ label = "Loading…", rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div role="status" aria-label={label} className="flex flex-col gap-3">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-16" />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}

export function EmptyState({ title, body, action }: { title: string; body?: string; action?: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <div className="mb-1 text-base font-semibold">{title}</div>
      {body && <p className="mx-auto mb-4 max-w-sm text-sm text-slate-500">{body}</p>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-wrap items-center gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3.5 text-sm text-amber-900">
      <span className="flex-1">{message}</span>
      {onRetry && (
        <button type="button" className="ghost" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}
