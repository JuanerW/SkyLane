// SkyLane mark: a route that starts on the ground and lifts into the sky (robot lane + drone lane).
// Same drawing as src/app/icon.svg; slate-900 tile with a sky-500 route, matching ui.css.
export function LogoMark({ size = 24, className = "" }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" className={className} aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="#0f172a" />
      <path d="M8 23.5h4.5c4.5 0 5-14 11.5-14" fill="none" stroke="#0ea5e9" strokeWidth="3" strokeLinecap="round" />
      <circle cx="24" cy="9.5" r="3" fill="#fff" />
      <circle cx="8" cy="23.5" r="1.75" fill="#7dd3fc" />
    </svg>
  );
}

export function Logo({ size = 24, className = "" }: { size?: number; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 ${className}`}>
      <LogoMark size={size} />
      <span>SkyLane</span>
    </span>
  );
}
